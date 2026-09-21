package com.scic.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.scic.platform.system.WarningService warningService;
    @MockBean com.scic.platform.intelligence.AiGateway aiGateway;

    @BeforeEach
    void ensureSecondSupplierOrder() {
        when(aiGateway.parse(anyMap(), anyString())).thenReturn(Map.of(
                "provider", "rule",
                "model_name", "deterministic-rule-v1",
                "prompt_version", "parse-v3-intent-schema-guarded",
                "original_text", "请采购 MAT-BOX-05 20 件",
                "fields", Map.of("material_code", "MAT-BOX-05", "quantity", 20),
                "missing_fields", List.of("required_date"),
                "warnings", List.of()));
        when(aiGateway.analysisReport(anyMap(), anyString())).thenReturn(analysisReportResponse());
        Integer users = jdbc.queryForObject("select count(*) from sys_user where username='supplier2'", Integer.class);
        if (users != null && users == 0) jdbc.update("insert into sys_user(username,display_name,password_hash,role_code,supplier_id) values('supplier2','供应商·林青','{noop}123456','SUPPLIER',2)");
        Integer orders = jdbc.queryForObject("select count(*) from purchase_order where order_no='PO-SCOPE-002'", Integer.class);
        if (orders != null && orders == 0) {
            Integer plans = jdbc.queryForObject("select count(*) from purchase_plan where plan_no='PLAN-SCOPE-002'", Integer.class);
            if (plans != null && plans == 0) jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-SCOPE-002','供应商隔离测试计划','ORDER_CREATED',100,2)");
            Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-SCOPE-002'", Long.class);
            jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-SCOPE-002',?,2,'PENDING_CONFIRMATION',100,'2026-09-10','test-scope-order',2)", planId);
            Long id = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCOPE-002'", Long.class);
            jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,unit_price,amount) values(?,2,10,10,100)", id);
        }
    }

    @Test @Order(1)
    void fourDemoRolesCanLoginAndBadPasswordFails() throws Exception {
        for (String username : new String[]{"admin", "buyer", "supplier", "manager"}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"123456\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
        }
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test @Order(2)
    void supplierCannotDiscoverAnotherSuppliersOrder() throws Exception {
        String token = token("supplier", "123456");
        long otherId = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCOPE-002'", Long.class);
        mvc.perform(get("/api/v1/procurement/orders/{id}", otherId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/procurement/orders").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].supplier_code").value("SUP-001"));
        for (String path : new String[]{"/api/v1/master-data/suppliers", "/api/v1/master-data/materials",
                "/api/v1/master-data/warehouses", "/api/v1/master-data/inventory",
                "/api/v1/system/warnings", "/api/v1/system/data-provenance"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics.openWarnings").value(0))
                .andExpect(jsonPath("$.data.metrics.highWarnings").value(0))
                .andExpect(jsonPath("$.data.warningDistribution").isEmpty());
    }

    @Test @Order(3)
    void wrongRoleAndIllegalTransitionAreBlocked() throws Exception {
        String buyer = token("buyer", "123456");
        mvc.perform(post("/api/v1/procurement/orders/1/actions").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"CONFIRM\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        String supplier = token("supplier", "123456");
        mvc.perform(post("/api/v1/procurement/orders/1/actions").header("Authorization", "Bearer " + supplier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"SHIP\",\"expectedVersion\":0}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("USE_DELIVERY_NOTICE_WORKFLOW"));
    }

    @Test @Order(4)
    void buyerCanCreateDemandAndCrossSupplierPlanIsRejected() throws Exception {
        String buyer = token("buyer", "123456");
        String futureDate = java.time.LocalDate.now().plusDays(10).toString();
        mvc.perform(post("/api/v1/procurement/demands").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialCode\":\"MAT-SEN-02\",\"quantity\":120,\"expectedDate\":\"" + futureDate + "\",\"priority\":\"NORMAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        long before = jdbc.queryForObject("select count(*) from purchase_plan", Long.class);
        String body = "{\"planName\":\"错误的跨供应商计划\",\"items\":[" +
                "{\"materialCode\":\"MAT-SEN-02\",\"supplierCode\":\"SUP-001\",\"quantity\":10,\"expectedDate\":\"" + futureDate + "\"}," +
                "{\"materialCode\":\"MAT-ALU-03\",\"supplierCode\":\"SUP-002\",\"quantity\":10,\"expectedDate\":\"" + futureDate + "\"}]}";
        mvc.perform(post("/api/v1/procurement/plans").header("Authorization", "Bearer " + buyer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(jdbc.queryForObject("select count(*) from purchase_plan", Long.class)).isEqualTo(before);
    }

    @Test @Order(5)
    void invalidCsvPreviewLeavesBusinessTablesUntouched() throws Exception {
        String buyer = token("buyer", "123456");
        long before = jdbc.queryForObject("select count(*) from material", Long.class);
        MockMultipartFile file = new MockMultipartFile("file", "materials.csv", "text/csv",
                "material_code,material_name\nBAD-1,缺字段物料\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/imports/preview").file(file).param("type", "MATERIAL")
                        .header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "test-invalid-csv"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED_VALIDATION"))
                .andExpect(jsonPath("$.data.failed_rows").value(1));
        assertThat(jdbc.queryForObject("select count(*) from material", Long.class)).isEqualTo(before);
    }

    @Test @Order(6)
    void receiptBeforeArrivalCannotChangeInventory() throws Exception {
        String buyer = token("buyer", "123456");
        java.math.BigDecimal before = jdbc.queryForObject("select on_hand_qty from inventory where warehouse_id=1 and material_id=1", java.math.BigDecimal.class);
        String body = "{\"orderId\":1,\"orderVersion\":0,\"warehouseCode\":\"WH-001\",\"items\":[{\"orderItemId\":1,\"receivedQty\":10,\"qualifiedQty\":10,\"rejectedQty\":0}]}";
        mvc.perform(post("/api/v1/collaboration/receipts").header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "receipt-too-early")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
        assertThat(jdbc.queryForObject("select on_hand_qty from inventory where warehouse_id=1 and material_id=1", java.math.BigDecimal.class)).isEqualByComparingTo(before);
        assertThat(jdbc.queryForObject("select count(*) from receipt where idempotency_key='receipt-too-early'", Long.class)).isZero();
    }

    @Test @Order(7)
    void bcl01CanCloseOrderDeliveryReceiptAndReconciliation() throws Exception {
        String supplier = token("supplier", "123456");
        String buyer = token("buyer", "123456");
        java.math.BigDecimal inventoryBefore = jdbc.queryForObject(
                "select on_hand_qty from inventory where warehouse_id=1 and material_id=1", java.math.BigDecimal.class);

        JsonNode confirmed = postJson("/api/v1/procurement/orders/1/actions", supplier,
                "{\"action\":\"CONFIRM\",\"expectedVersion\":0,\"note\":\"集成测试确认\"}", null);
        JsonNode prepared = postJson("/api/v1/procurement/orders/1/actions", supplier,
                "{\"action\":\"PREPARE_SHIPMENT\",\"expectedVersion\":" + confirmed.path("version").asInt() + "}", null);
        assertThat(prepared.path("status").asText()).isEqualTo("PENDING_SHIPMENT");

        JsonNode notice = postJson("/api/v1/collaboration/delivery-notices", supplier,
                "{\"orderId\":1,\"expectedArrivalAt\":\"2026-09-05T10:00:00+08:00\",\"carrierName\":\"测试物流\",\"trackingNo\":\"TEST-001\",\"items\":[]}", null);
        JsonNode submitted = postJson("/api/v1/collaboration/delivery-notices/" + notice.path("id").asLong() + "/actions", supplier,
                "{\"action\":\"SUBMIT\",\"expectedVersion\":" + notice.path("version").asInt() + "}", null);
        JsonNode dispatched = postJson("/api/v1/collaboration/delivery-notices/" + notice.path("id").asLong() + "/actions", supplier,
                "{\"action\":\"DISPATCH\",\"expectedVersion\":" + submitted.path("version").asInt() + "}", null);
        JsonNode arrivedNotice = postJson("/api/v1/collaboration/delivery-notices/" + notice.path("id").asLong() + "/actions", buyer,
                "{\"action\":\"ARRIVE\",\"expectedVersion\":" + dispatched.path("version").asInt() + "}", null);
        assertThat(arrivedNotice.path("status").asText()).isEqualTo("ARRIVED");

        JsonNode arrivedOrder = getData("/api/v1/procurement/orders/1", buyer);
        JsonNode orderItem = arrivedOrder.path("items").get(0);
        String receiptBody = "{\"orderId\":1,\"deliveryNoticeId\":" + notice.path("id").asLong() +
                ",\"orderVersion\":" + arrivedOrder.path("version").asInt() +
                ",\"warehouseCode\":\"WH-001\",\"notes\":\"集成测试收货\",\"items\":[{\"orderItemId\":" +
                orderItem.path("id").asLong() + ",\"receivedQty\":" + orderItem.path("quantity").decimalValue().toPlainString() +
                ",\"qualifiedQty\":" + orderItem.path("quantity").decimalValue().toPlainString() +
                ",\"rejectedQty\":0}]}";
        JsonNode receipt = postJson("/api/v1/collaboration/receipts", buyer, receiptBody, "bcl01-receipt");
        assertThat(receipt.path("status").asText()).isEqualTo("COMPLETED");
        JsonNode receiptReplay = postJson("/api/v1/collaboration/receipts", buyer, receiptBody, "bcl01-receipt");
        assertThat(receiptReplay.path("id").asLong()).isEqualTo(receipt.path("id").asLong());

        JsonNode receivedOrder = getData("/api/v1/procurement/orders/1", buyer);
        JsonNode reconciliation = postJson("/api/v1/collaboration/reconciliations", buyer,
                "{\"orderId\":1,\"orderVersion\":" + receivedOrder.path("version").asInt() + "}", "bcl01-reconciliation");
        JsonNode reconciliationReplay = postJson("/api/v1/collaboration/reconciliations", buyer,
                "{\"orderId\":1,\"orderVersion\":" + receivedOrder.path("version").asInt() + "}", "bcl01-reconciliation");
        assertThat(reconciliationReplay.path("id").asLong()).isEqualTo(reconciliation.path("id").asLong());
        JsonNode supplierConfirmed = postJson("/api/v1/collaboration/reconciliations/" + reconciliation.path("id").asLong() + "/actions", supplier,
                "{\"action\":\"CONFIRM\",\"expectedVersion\":" + reconciliation.path("version").asInt() + "}", null);
        JsonNode completed = postJson("/api/v1/collaboration/reconciliations/" + reconciliation.path("id").asLong() + "/actions", buyer,
                "{\"action\":\"COMPLETE\",\"expectedVersion\":" + supplierConfirmed.path("version").asInt() + "}", null);

        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(getData("/api/v1/procurement/orders/1", buyer).path("status").asText()).isEqualTo("COMPLETED");
        java.math.BigDecimal inventoryAfter = jdbc.queryForObject(
                "select on_hand_qty from inventory where warehouse_id=1 and material_id=1", java.math.BigDecimal.class);
        assertThat(inventoryAfter).isEqualByComparingTo(inventoryBefore.add(orderItem.path("quantity").decimalValue()));
        assertThat(jdbc.queryForObject("select count(*) from inventory_transaction where source_type='RECEIPT'", Long.class)).isPositive();
    }

    @Test @Order(8)
    void adminCannotWriteBusinessDataAndUnknownEndpointIs404() throws Exception {
        String admin = token("admin", "123456");
        String futureDate = java.time.LocalDate.now().plusDays(10).toString();
        mvc.perform(post("/api/v1/procurement/demands").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialCode\":\"MAT-SEN-02\",\"quantity\":1,\"expectedDate\":\"" + futureDate + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        String warningStatus = jdbc.queryForObject("select status from warning_record where id=1", String.class);
        mvc.perform(post("/api/v1/system/warnings/1/handle").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"管理员不应处理\",\"close\":false}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("select status from warning_record where id=1", String.class)).isEqualTo(warningStatus);
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test @Order(9)
    void forecastRunsAreFilteredBySelectedMaterial() throws Exception {
        jdbc.update("insert into forecast_run(run_no,material_id,as_of_date,horizon_days,model_name,data_hash,data_label,status) select 'FC-FILTER-BOX',id,current_date,14,'MA7','box-hash','DEMO_SYNTHETIC','COMPLETED' from material where material_code='MAT-BOX-05'");
        jdbc.update("insert into forecast_run(run_no,material_id,as_of_date,horizon_days,model_name,data_hash,data_label,status) select 'FC-FILTER-CPU',id,current_date,14,'MA7','cpu-hash','DEMO_SYNTHETIC','COMPLETED' from material where material_code='MAT-CPU-01'");
        String buyer = token("buyer", "123456");
        JsonNode rows = getData("/api/v1/intelligence/forecast-runs?materialCode=MAT-BOX-05", buyer);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.path("material_code").asText()).isEqualTo("MAT-BOX-05"));
    }

    @Test @Order(10)
    void correctedAiPreviewCanBeConfirmed() throws Exception {
        String buyer = token("buyer", "123456");
        JsonNode preview = postJson("/api/v1/intelligence/parse-previews", buyer,
                "{\"taskType\":\"PURCHASE_DEMAND\",\"text\":\"请采购 MAT-BOX-05 20 件\"}", null);
        assertThat(preview.path("provider").asText()).isEqualTo("rule");
        assertThat(preview.path("modelName").asText()).isEqualTo("deterministic-rule-v1");
        assertThat(preview.path("promptVersion").asText()).isEqualTo("parse-v3-intent-schema-guarded");
        long id = preview.path("previewId").asLong();
        String requiredDate = java.time.LocalDate.now().plusDays(10).toString();
        String correction = "{\"expectedVersion\":0,\"normalizedFields\":{\"material_code\":\"MAT-BOX-05\",\"quantity\":20,\"required_date\":\"" + requiredDate + "\"}}";
        String correctedResponse = mvc.perform(patch("/api/v1/intelligence/parse-previews/{id}", id)
                        .header("Authorization", "Bearer " + buyer).contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.schemaValid").value(true))
                .andExpect(jsonPath("$.data.businessValid").value(true)).andExpect(jsonPath("$.data.previewVersion").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(correctedResponse).path("data").path("normalizedFields").path("material_code").asText()).isEqualTo("MAT-BOX-05");
        JsonNode confirmed = postJson("/api/v1/intelligence/parse-previews/" + id + "/confirm", buyer,
                "{\"expectedVersion\":1}", "ai-correction-confirm-" + id);
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("targetType").asText()).isEqualTo("PURCHASE_DEMAND");
    }

    @Test @Order(11)
    void forecastSuggestionCanBeAdoptedOnceAndTracedToDemand() throws Exception {
        jdbc.update("insert into forecast_run(run_no,material_id,as_of_date,horizon_days,model_name,data_hash,data_label,status) select 'FC-ADOPT-001',id,current_date,14,'MA7','adopt-hash','DEMO_SYNTHETIC','SUCCEEDED' from material where material_code='MAT-BOX-05'");
        Long runId = jdbc.queryForObject("select id from forecast_run where run_no='FC-ADOPT-001'", Long.class);
        jdbc.update("insert into forecast_result(run_id,forecast_date,predicted_qty,raw_predicted_qty,suggested_order_qty) values(?,dateadd('day',1,current_date),10,10,125)", runId);
        String buyer = token("buyer", "123456");
        String expectedDate = java.time.LocalDate.now().plusDays(10).toString();
        String body = "{\"expectedVersion\":0,\"expectedDate\":\"" + expectedDate + "\",\"priority\":\"HIGH\",\"note\":\"预测建议采纳测试\"}";
        JsonNode demand = postJson("/api/v1/intelligence/forecast-runs/" + runId + "/adopt", buyer, body, "forecast-adopt-1");
        assertThat(demand.path("source_type").asText()).isEqualTo("FORECAST");
        assertThat(demand.path("quantity").decimalValue()).isEqualByComparingTo("125");
        JsonNode replay = postJson("/api/v1/intelligence/forecast-runs/" + runId + "/adopt", buyer, body, "forecast-adopt-2");
        assertThat(replay.path("id").asLong()).isEqualTo(demand.path("id").asLong());
        assertThat(jdbc.queryForObject("select count(*) from purchase_demand where forecast_run_id=?", Long.class, runId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select suggestion_status from forecast_run where id=?", String.class, runId)).isEqualTo("ADOPTED");
        JsonNode runs = getData("/api/v1/intelligence/forecast-runs", buyer);
        JsonNode listed = null;
        for (JsonNode candidate : runs) if (candidate.path("id").asLong() == runId) listed = candidate;
        assertThat(listed).isNotNull();
        assertThat(listed.path("suggested_order_qty").decimalValue()).isEqualByComparingTo("125");
        assertThat(listed.path("suggestion_status").asText()).isEqualTo("ADOPTED");
    }

    @Test @Order(12)
    void partialReceiptRequiresQualifiedCompletionBeforeReconciliation() throws Exception {
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-PARTIAL-001','部分收货测试计划','ORDER_CREATED',428,2)");
        Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-PARTIAL-001'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-PARTIAL-001',?,1,'ARRIVED',428,current_date,'partial-order',2)", planId);
        Long orderId = jdbc.queryForObject("select id from purchase_order where order_no='PO-PARTIAL-001'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,unit_price,amount) values(?,2,10,42.8,428)", orderId);
        Long itemId = jdbc.queryForObject("select id from purchase_order_item where order_id=?", Long.class, orderId);
        jdbc.update("insert into delivery_notice(notice_no,order_id,supplier_id,status,expected_arrival_at,source_type,created_by) values('DN-PARTIAL-001',?,1,'ARRIVED',current_timestamp,'FORM',3)", orderId);
        Long noticeId = jdbc.queryForObject("select id from delivery_notice where notice_no='DN-PARTIAL-001'", Long.class);
        jdbc.update("insert into delivery_notice_item(notice_id,order_item_id,quantity) values(?,?,10)", noticeId, itemId);
        String buyer = token("buyer", "123456");
        String first = "{\"orderId\":" + orderId + ",\"deliveryNoticeId\":" + noticeId + ",\"orderVersion\":0,\"warehouseCode\":\"WH-001\",\"items\":[{\"orderItemId\":" + itemId + ",\"receivedQty\":4,\"qualifiedQty\":3,\"rejectedQty\":1,\"varianceReason\":\"一件外观不合格，后续补足\"}]}";
        postJson("/api/v1/collaboration/receipts", buyer, first, "partial-receipt-1");
        JsonNode partial = getData("/api/v1/procurement/orders/" + orderId, buyer);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIALLY_RECEIVED");
        assertThat(jdbc.queryForObject("select status from delivery_notice where id=?", String.class, noticeId)).isEqualTo("ARRIVED");
        mvc.perform(post("/api/v1/collaboration/reconciliations").header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "partial-too-early")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":" + orderId + ",\"orderVersion\":" + partial.path("version").asInt() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
        String second = "{\"orderId\":" + orderId + ",\"deliveryNoticeId\":" + noticeId + ",\"orderVersion\":" + partial.path("version").asInt() + ",\"warehouseCode\":\"WH-001\",\"items\":[{\"orderItemId\":" + itemId + ",\"receivedQty\":7,\"qualifiedQty\":7,\"rejectedQty\":0}]}";
        postJson("/api/v1/collaboration/receipts", buyer, second, "partial-receipt-2");
        assertThat(getData("/api/v1/procurement/orders/" + orderId, buyer).path("status").asText()).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select status from delivery_notice where id=?", String.class, noticeId)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("select received_qty from purchase_order_item where id=?", java.math.BigDecimal.class, itemId)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from warning_record where source_key=?", Long.class, "RECEIPT_VARIANCE:RECEIPT:" + jdbc.queryForObject("select id from receipt where idempotency_key='partial-receipt-1'", Long.class))).isEqualTo(1L);
    }

    @Test @Order(13)
    void dynamicStockWarningDoesNotReopenUntilConditionRecurs() throws Exception {
        Long materialId = jdbc.queryForObject("select id from material where material_code='MAT-SEN-02'", Long.class);
        jdbc.update("update inventory set on_hand_qty=0,reserved_qty=0,in_transit_qty=0 where material_id=?", materialId);
        warningService.refreshRuleWarnings();
        String key = "STOCK_SHORTAGE:MATERIAL:" + materialId;
        Long warningId = jdbc.queryForObject("select id from warning_record where source_key=?", Long.class, key);
        String buyer = token("buyer", "123456");
        mvc.perform(post("/api/v1/system/warnings/{id}/handle", warningId).header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"人工确认暂不采购\",\"close\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        warningService.refreshRuleWarnings();
        assertThat(jdbc.queryForObject("select status from warning_record where id=?", String.class, warningId)).isEqualTo("CLOSED");
        jdbc.update("update inventory set on_hand_qty=400,reserved_qty=0,in_transit_qty=0 where material_id=?", materialId);
        warningService.refreshRuleWarnings();
        assertThat(jdbc.queryForObject("select condition_active from warning_record where id=?", Boolean.class, warningId)).isFalse();
        jdbc.update("update inventory set on_hand_qty=0 where material_id=?", materialId);
        warningService.refreshRuleWarnings();
        assertThat(jdbc.queryForObject("select status from warning_record where id=?", String.class, warningId)).isEqualTo("OPEN");
    }

    @Test @Order(14)
    void onePlanCreatesOnlyOneOrderAndIdempotencyKeyIsLiteral() throws Exception {
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-IDEM-001','订单幂等测试','APPROVED',125,2)");
        Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-IDEM-001'", Long.class);
        jdbc.update("insert into purchase_plan_item(plan_id,material_id,supplier_id,quantity,unit_price,expected_date) values(?,5,3,10,12.5,dateadd('day',10,current_date))", planId);
        String buyer = token("buyer", "123456");
        JsonNode created = postJson("/api/v1/procurement/plans/" + planId + "/generate-orders", buyer, "{\"expectedVersion\":0}", "literal-%_key");
        assertThat(created.size()).isEqualTo(1);
        JsonNode replay = postJson("/api/v1/procurement/plans/" + planId + "/generate-orders", buyer, "{\"expectedVersion\":0}", "literal-%_key");
        assertThat(replay.get(0).path("id").asLong()).isEqualTo(created.get(0).path("id").asLong());
        mvc.perform(post("/api/v1/procurement/plans/{id}/generate-orders", planId).header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "other-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_GENERATED"));
        assertThat(jdbc.queryForObject("select count(*) from purchase_order where plan_id=?", Long.class, planId)).isEqualTo(1L);
    }

    @Test @Order(15)
    void noticeQuantityOverReceiptAndPartialOrderDelayUseRealRemainingQuantity() throws Exception {
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-EDGE-015','通知余量边界','ORDER_CREATED',4280,2)");
        Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-EDGE-015'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-EDGE-015',?,1,'PARTIALLY_RECEIVED',4280,dateadd('day',-2,current_date),'edge-order-015',2)", planId);
        Long orderId = jdbc.queryForObject("select id from purchase_order where order_no='PO-EDGE-015'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,unit_price,amount) values(?,2,100,42.8,4280)", orderId);
        Long itemId = jdbc.queryForObject("select id from purchase_order_item where order_id=?", Long.class, orderId);
        jdbc.update("insert into delivery_notice(notice_no,order_id,supplier_id,status,expected_arrival_at,source_type,created_by) values('DN-EDGE-015',?,1,'ARRIVED',current_timestamp,'FORM',3)", orderId);
        Long noticeId = jdbc.queryForObject("select id from delivery_notice where notice_no='DN-EDGE-015'", Long.class);
        jdbc.update("insert into delivery_notice_item(notice_id,order_item_id,quantity) values(?,?,10)", noticeId, itemId);

        String buyer = token("buyer", "123456");
        String body = "{\"orderId\":" + orderId + ",\"deliveryNoticeId\":" + noticeId + ",\"orderVersion\":0,\"warehouseCode\":\"WH-001\",\"items\":[{\"orderItemId\":" + itemId + ",\"receivedQty\":50,\"qualifiedQty\":10,\"rejectedQty\":40,\"varianceReason\":\"越界验收\"}]}";
        mvc.perform(post("/api/v1/collaboration/receipts").header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "edge-receipt-015")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"));
        assertThat(jdbc.queryForObject("select count(*) from receipt where idempotency_key='edge-receipt-015'", Long.class)).isZero();

        JsonNode notices = getData("/api/v1/collaboration/delivery-notices", buyer);
        JsonNode listed = null;
        for (JsonNode candidate : notices) if (candidate.path("id").asLong() == noticeId) listed = candidate;
        assertThat(listed).isNotNull();
        assertThat(listed.path("order_id").asLong()).isEqualTo(orderId);
        assertThat(listed.path("quantity").decimalValue()).isEqualByComparingTo("10");

        warningService.refreshRuleWarnings();
        String warningKey = "ORDER_DELAY:PURCHASE_ORDER:" + orderId;
        assertThat(jdbc.queryForObject("select condition_active from warning_record where source_key=?", Boolean.class, warningKey)).isTrue();
        assertThat(jdbc.queryForObject("select reason_text from warning_record where source_key=?", String.class, warningKey)).contains("尚有100未履约");
    }

    @Test @Order(16)
    void supplierPerformanceUsesFinalReceiptTimeForSplitDelivery() throws Exception {
        jdbc.update("insert into supplier(supplier_code,supplier_name,status,on_time_rate) values('SUP-PERF-016','分批交付测试供应商','ACTIVE',1)");
        Long supplierId = jdbc.queryForObject("select id from supplier where supplier_code='SUP-PERF-016'", Long.class);
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-PERF-016','分批交付绩效','ORDER_CREATED',100,2)");
        Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-PERF-016'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-PERF-016',?,?,'RECONCILING',100,current_date,'perf-order-016',2)", planId, supplierId);
        Long orderId = jdbc.queryForObject("select id from purchase_order where order_no='PO-PERF-016'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,received_qty,unit_price,amount) values(?,5,10,10,10,100)", orderId);
        Long itemId = jdbc.queryForObject("select id from purchase_order_item where order_id=?", Long.class, orderId);
        jdbc.update("insert into receipt(receipt_no,order_id,warehouse_id,status,received_by,received_at,idempotency_key) values('RCV-PERF-EARLY',?,1,'COMPLETED',2,dateadd('day',-1,current_timestamp),'perf-early-016')", orderId);
        Long earlyReceipt = jdbc.queryForObject("select id from receipt where receipt_no='RCV-PERF-EARLY'", Long.class);
        jdbc.update("insert into receipt_item(receipt_id,order_item_id,material_id,ordered_qty,received_qty,qualified_qty,rejected_qty,difference_qty) values(?,?,5,5,5,5,0,0)", earlyReceipt, itemId);
        jdbc.update("insert into receipt(receipt_no,order_id,warehouse_id,status,received_by,received_at,idempotency_key) values('RCV-PERF-LATE',?,1,'COMPLETED',2,dateadd('day',1,current_timestamp),'perf-late-016')", orderId);
        Long lateReceipt = jdbc.queryForObject("select id from receipt where receipt_no='RCV-PERF-LATE'", Long.class);
        jdbc.update("insert into receipt_item(receipt_id,order_item_id,material_id,ordered_qty,received_qty,qualified_qty,rejected_qty,difference_qty) values(?,?,5,5,5,5,0,0)", lateReceipt, itemId);
        jdbc.update("insert into reconciliation(reconciliation_no,order_id,status,order_amount,received_amount,difference_amount,buyer_confirmed,supplier_confirmed) values('REC-PERF-016',?,'CONFIRMED',100,100,0,true,true)", orderId);
        Long reconciliationId = jdbc.queryForObject("select id from reconciliation where reconciliation_no='REC-PERF-016'", Long.class);

        String buyer = token("buyer", "123456");
        postJson("/api/v1/collaboration/reconciliations/" + reconciliationId + "/actions", buyer,
                "{\"action\":\"COMPLETE\",\"expectedVersion\":0}", null);
        assertThat(jdbc.queryForObject("select on_time_rate from supplier where id=?", java.math.BigDecimal.class, supplierId)).isEqualByComparingTo("0");
    }

    @Test @Order(17)
    void procurementScenarioSimulatesWithoutBusinessMutationAndBuyerCanAdoptOnce() throws Exception {
        Long materialId = jdbc.queryForObject("select id from material where material_code='MAT-BOX-05'", Long.class);
        jdbc.update("delete from inventory where material_id=?", materialId);
        jdbc.update("insert into inventory(warehouse_id,material_id,on_hand_qty,reserved_qty,in_transit_qty) values(1,?,100,0,100)", materialId);
        jdbc.update("insert into forecast_run(run_no,material_id,as_of_date,horizon_days,model_name,model_version,data_hash,data_label,status) values('FC-SCENARIO-017',?,current_date,14,'MA7','test-v1','scenario-hash-017','DEMO_SYNTHETIC','SUCCEEDED')", materialId);
        Long runId = jdbc.queryForObject("select id from forecast_run where run_no='FC-SCENARIO-017'", Long.class);
        for (int day = 1; day <= 14; day++) {
            jdbc.update("insert into forecast_result(run_id,forecast_date,predicted_qty,raw_predicted_qty,suggested_order_qty) values(?,dateadd('day',?,current_date),10,10,0)", runId, day);
        }
        String buyer = token("buyer", "123456");
        Long demandsBefore = jdbc.queryForObject("select count(*) from purchase_demand", Long.class);
        String payload = "{\"scenarioName\":\"需求翻倍并延迟到货\",\"materialCode\":\"MAT-BOX-05\",\"forecastRunId\":" + runId + ",\"demandChangePercent\":100,\"supplierDelayDays\":10,\"safetyStockChangePercent\":0,\"qualificationRatePercent\":80,\"priceChangePercent\":10}";
        JsonNode scenario = postJson("/api/v1/intelligence/scenarios", buyer, payload, "scenario-simulate-017");
        long scenarioId = scenario.path("id").asLong();
        assertThat(scenario.path("status").asText()).isEqualTo("PREVIEW");
        assertThat(scenario.path("baseline").path("totalDemand").decimalValue()).isEqualByComparingTo("140");
        assertThat(scenario.path("baseline").path("recommendedOrderQty").decimalValue()).isEqualByComparingTo("200");
        assertThat(scenario.path("simulated").path("totalDemand").decimalValue()).isEqualByComparingTo("280");
        assertThat(scenario.path("simulated").path("recommendedOrderQty").decimalValue())
                .isGreaterThan(scenario.path("baseline").path("recommendedOrderQty").decimalValue());
        assertThat(scenario.path("simulated").path("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(jdbc.queryForObject("select count(*) from purchase_demand", Long.class)).isEqualTo(demandsBefore);

        JsonNode replay = postJson("/api/v1/intelligence/scenarios", buyer, payload, "scenario-simulate-017");
        assertThat(replay.path("id").asLong()).isEqualTo(scenarioId);
        String expectedDate = java.time.LocalDate.now().plusDays(20).toString();
        String adoptBody = "{\"expectedVersion\":0,\"expectedDate\":\"" + expectedDate + "\",\"priority\":\"HIGH\",\"note\":\"集成测试确认\"}";
        JsonNode adopted = postJson("/api/v1/intelligence/scenarios/" + scenarioId + "/adopt", buyer, adoptBody, "scenario-adopt-017");
        assertThat(adopted.path("status").asText()).isEqualTo("ADOPTED");
        assertThat(adopted.path("adoptedDemand").path("source_type").asText()).isEqualTo("SCENARIO");
        assertThat(adopted.path("adoptedDemand").path("source_ref").asText()).isEqualTo(scenario.path("scenarioNo").asText());
        JsonNode adoptedReplay = postJson("/api/v1/intelligence/scenarios/" + scenarioId + "/adopt", buyer, adoptBody, "scenario-adopt-017");
        assertThat(adoptedReplay.path("adoptedDemand").path("id").asLong()).isEqualTo(adopted.path("adoptedDemand").path("id").asLong());

        String manager = token("manager", "123456");
        assertThat(getData("/api/v1/intelligence/scenarios", manager)).isNotEmpty();
        mvc.perform(post("/api/v1/intelligence/scenarios/{id}/adopt", scenarioId).header("Authorization", "Bearer " + manager).header("Idempotency-Key", "manager-cannot-adopt")
                        .contentType(MediaType.APPLICATION_JSON).content(adoptBody))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        String supplier = token("supplier", "123456");
        mvc.perform(post("/api/v1/intelligence/scenarios").header("Authorization", "Bearer " + supplier).header("Idempotency-Key", "supplier-cannot-simulate")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test @Order(18)
    void procurementScenarioPersistsExpiryAndCoversIsolationIdempotencyAndOrderScope() throws Exception {
        Long materialId = jdbc.queryForObject("select id from material where material_code='MAT-BOX-05'", Long.class);
        Long runId = jdbc.queryForObject("select id from forecast_run where run_no='FC-SCENARIO-017'", Long.class);
        String buyer = token("buyer", "123456");
        String payload = "{\"scenarioName\":\"情景边界测试\",\"materialCode\":\"MAT-BOX-05\",\"forecastRunId\":" + runId + ",\"demandChangePercent\":20,\"supplierDelayDays\":5,\"safetyStockChangePercent\":0,\"qualificationRatePercent\":100,\"priceChangePercent\":0}";

        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-SCN-ARR-018','已到达不受延迟影响','ORDER_CREATED',100,2)");
        Long arrivedPlan = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-SCN-ARR-018'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-SCN-ARR-018',?,1,'ARRIVED',100,dateadd('day',2,current_date),'scn-arr-018',2)", arrivedPlan);
        Long arrivedOrder = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCN-ARR-018'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,received_qty,unit_price,amount) values(?,?,10,0,10,100)", arrivedOrder, materialId);
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-SCN-SHIP-018','运输中受延迟影响','ORDER_CREATED',100,2)");
        Long shippedPlan = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-SCN-SHIP-018'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-SCN-SHIP-018',?,1,'SHIPPED',100,dateadd('day',2,current_date),'scn-ship-018',2)", shippedPlan);
        Long shippedOrder = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCN-SHIP-018'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,received_qty,unit_price,amount) values(?,?,10,0,10,100)", shippedOrder, materialId);
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values('PLAN-SCN-ZERO-018','未到货状态但无剩余量','ORDER_CREATED',100,2)");
        Long zeroRemainingPlan = jdbc.queryForObject("select id from purchase_plan where plan_no='PLAN-SCN-ZERO-018'", Long.class);
        jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-SCN-ZERO-018',?,1,'SHIPPED',100,dateadd('day',2,current_date),'scn-zero-remain-018',2)", zeroRemainingPlan);
        Long zeroRemainingOrder = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCN-ZERO-018'", Long.class);
        jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,received_qty,unit_price,amount) values(?,?,10,10,10,100)", zeroRemainingOrder, materialId);

        JsonNode scoped = postJson("/api/v1/intelligence/scenarios", buyer, payload, "  scenario-scope-018  ");
        assertThat(scoped.path("affectedOrders").findValuesAsText("orderNo"))
                .contains("PO-SCN-SHIP-018")
                .doesNotContain("PO-SCN-ARR-018", "PO-SCN-ZERO-018");
        JsonNode normalizedReplay = postJson("/api/v1/intelligence/scenarios", buyer, payload, "scenario-scope-018");
        assertThat(normalizedReplay.path("id").asLong()).isEqualTo(scoped.path("id").asLong());

        JsonNode expiring = postJson("/api/v1/intelligence/scenarios", buyer, payload, "scenario-expire-018");
        long expiredId = expiring.path("id").asLong();
        jdbc.update("update procurement_scenario set expires_at=dateadd('minute',-1,current_timestamp) where id=?", expiredId);
        String adoptBody = "{\"expectedVersion\":0,\"expectedDate\":\"" + java.time.LocalDate.now().plusDays(20) + "\",\"priority\":\"HIGH\"}";
        mvc.perform(post("/api/v1/intelligence/scenarios/{id}/adopt", expiredId).header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "scenario-expired-adopt-018")
                        .contentType(MediaType.APPLICATION_JSON).content(adoptBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SCENARIO_EXPIRED"));
        assertThat(jdbc.queryForObject("select status from procurement_scenario where id=?", String.class, expiredId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select version from procurement_scenario where id=?", Integer.class, expiredId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from operation_log where action_code='EXPIRE_PROCUREMENT_SCENARIO' and target_type='PROCUREMENT_SCENARIO' and target_id=? and before_state='PREVIEW' and after_state='EXPIRED'", Long.class, expiredId)).isEqualTo(1);
        JsonNode expiredDetail = getData("/api/v1/intelligence/scenarios/" + expiredId, buyer);
        assertThat(expiredDetail.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(expiredDetail.path("version").asInt()).isEqualTo(1);

        JsonNode staleVersion = postJson("/api/v1/intelligence/scenarios", buyer, payload, "scenario-version-018");
        mvc.perform(post("/api/v1/intelligence/scenarios/{id}/adopt", staleVersion.path("id").asLong()).header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "scenario-version-adopt-018")
                        .contentType(MediaType.APPLICATION_JSON).content(adoptBody.replace("\"expectedVersion\":0", "\"expectedVersion\":99")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        jdbc.update("delete from inventory where material_id=?", materialId);
        jdbc.update("insert into inventory(warehouse_id,material_id,on_hand_qty,reserved_qty,in_transit_qty) values(1,?,1000,0,0)", materialId);
        String zeroPayload = payload.replace("\"demandChangePercent\":20", "\"demandChangePercent\":-80").replace("\"safetyStockChangePercent\":0", "\"safetyStockChangePercent\":-100");
        JsonNode zero = postJson("/api/v1/intelligence/scenarios", buyer, zeroPayload, "scenario-zero-018");
        assertThat(zero.path("simulated").path("recommendedOrderQty").decimalValue()).isEqualByComparingTo("0");
        mvc.perform(post("/api/v1/intelligence/scenarios/{id}/adopt", zero.path("id").asLong()).header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "scenario-zero-adopt-018")
                        .contentType(MediaType.APPLICATION_JSON).content(adoptBody))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("NO_PURCHASE_SUGGESTION"));

        mvc.perform(post("/api/v1/intelligence/scenarios").header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "x".repeat(81))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        jdbc.update("insert into sys_user(username,display_name,password_hash,role_code,enabled) values('buyer2','采购专员·隔离测试','{noop}123456','BUYER',true)");
        String buyer2 = token("buyer2", "123456");
        assertThat(getData("/api/v1/intelligence/scenarios", buyer2)).isEmpty();

        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<Long>) () -> {
                start.await(5, TimeUnit.SECONDS);
                String body = mvc.perform(post("/api/v1/intelligence/scenarios").header("Authorization", "Bearer " + buyer).header("Idempotency-Key", "scenario-concurrent-018")
                                .contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                return json.readTree(body).path("data").path("id").asLong();
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from procurement_scenario where simulation_idempotency_key='scenario-concurrent-018'", Long.class)).isEqualTo(1);
    }

    @Test @Order(19)
    void disabledAccountImmediatelyInvalidatesPreviouslyIssuedToken() throws Exception {
        String username = "token-revoke-019";
        jdbc.update("delete from sys_user where username=?", username);
        jdbc.update("insert into sys_user(username,display_name,password_hash,role_code,enabled) values(?,?,?,?,true)",
                username, "令牌失效测试账号", "{noop}12345678", "BUYER");
        String issuedToken = token(username, "12345678");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("BUYER"));

        jdbc.update("update sys_user set enabled=false,version=version+1 where username=?", username);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"12345678\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test @Order(20)
    void localAnalysisReportPersistsGroundedSnapshotAndIsIdempotent() throws Exception {
        String buyer = token("buyer", "123456");
        String key = "analysis-report-020";
        JsonNode first = postJson("/api/v1/intelligence/analysis-reports", buyer, "{}", key);
        JsonNode second = postJson("/api/v1/intelligence/analysis-reports", buyer, "{}", key);
        assertThat(second.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(first.path("sections").size()).isEqualTo(7);
        assertThat(first.path("context").path("metrics").path("active_orders").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(first.path("data_fingerprint").asText()).hasSize(64);
        assertThat(jdbc.queryForObject("select count(*) from supply_chain_analysis_report where idempotency_key=?", Long.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from operation_log where action_code='GENERATE_ANALYSIS_REPORT' and target_id=?", Long.class, first.path("id").asLong())).isEqualTo(1);

        String supplier = token("supplier", "123456");
        mvc.perform(get("/api/v1/intelligence/analysis-reports").header("Authorization", "Bearer " + supplier))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test @Order(21)
    void workQueueIsRoleScopedReadonlyProjection() throws Exception {
        long logsBefore = jdbc.queryForObject("select count(*) from operation_log", Long.class);
        JsonNode buyerQueue = getData("/api/v1/work-queue", token("buyer", "123456"));
        assertThat(buyerQueue.path("role").asText()).isEqualTo("BUYER");
        assertThat(buyerQueue.path("tasks").isArray()).isTrue();
        assertThat(buyerQueue.path("summary").path("total").asInt()).isEqualTo(buyerQueue.path("tasks").size());
        buyerQueue.path("tasks").forEach(task -> {
            assertThat(task.path("task_key").asText()).contains(":");
            assertThat(task.path("route").asText()).startsWith("/");
        });

        String supplierResponse = mvc.perform(get("/api/v1/work-queue")
                        .header("Authorization", "Bearer " + token("supplier", "123456")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("SUPPLIER"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(supplierResponse).doesNotContain("PO-SCOPE-002");

        mvc.perform(get("/api/v1/work-queue").header("Authorization", "Bearer " + token("admin", "123456")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("select count(*) from operation_log", Long.class)).isEqualTo(logsBefore);
    }

    @Test @Order(22)
    void orderTimelineIsTraceableScopedAndReadonly() throws Exception {
        long ownOrderId = jdbc.queryForObject("select id from purchase_order where order_no='PO-202608-001'", Long.class);
        long otherOrderId = jdbc.queryForObject("select id from purchase_order where order_no='PO-SCOPE-002'", Long.class);
        long logsBefore = jdbc.queryForObject("select count(*) from operation_log", Long.class);

        JsonNode buyerTimeline = getData("/api/v1/procurement/orders/" + ownOrderId + "/timeline", token("buyer", "123456"));
        assertThat(buyerTimeline.path("order_no").asText()).isEqualTo("PO-202608-001");
        assertThat(buyerTimeline.path("stages").size()).isEqualTo(6);
        assertThat(buyerTimeline.path("events").isArray()).isTrue();
        assertThat(buyerTimeline.path("events").size()).isGreaterThan(0);
        assertThat(buyerTimeline.path("events").get(0).path("after_state").asText()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(buyerTimeline.path("read_only").asBoolean()).isTrue();

        mvc.perform(get("/api/v1/procurement/orders/{id}/timeline", ownOrderId)
                        .header("Authorization", "Bearer " + token("supplier", "123456")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.supplier_name").value("华东精密电子有限公司"));
        mvc.perform(get("/api/v1/procurement/orders/{id}/timeline", otherOrderId)
                        .header("Authorization", "Bearer " + token("supplier", "123456")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/procurement/orders/{id}/timeline", ownOrderId)
                        .header("Authorization", "Bearer " + token("admin", "123456")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from operation_log", Long.class)).isEqualTo(logsBefore);
    }

    private static Map<String, Object> analysisReportResponse() {
        List<Map<String, Object>> sections = List.of(
                Map.of("key", "executive_summary", "title", "经营摘要", "content", "经营状态需要持续复核。"),
                Map.of("key", "demand_inventory", "title", "需求与库存", "content", "库存风险以事实快照为准。"),
                Map.of("key", "supplier_fulfillment", "title", "供应商与履约", "content", "交付情况需结合订单核对。"),
                Map.of("key", "reconciliation_finance", "title", "对账与资金风险", "content", "差异需回到明细处理。"),
                Map.of("key", "warning_risk", "title", "异常与预警", "content", "预警尚不代表处置完成。"),
                Map.of("key", "recommendations", "title", "处置建议", "content", "建议按风险优先级推进。"),
                Map.of("key", "boundary", "title", "分析边界", "content", "报告只读且不会自动改变业务。"));
        return Map.of(
                "provider", "rule", "model_name", "supply-chain-rule-report-v1",
                "prompt_version", "analysis-v2-local-rank-rule-facts", "fallback_reason", "测试规则回退",
                "warnings", List.of("报告只读"), "sections", sections,
                "priority_actions", List.of(Map.of("code", "MONITOR_OPERATIONS", "level", "LOW", "title", "保持监测", "rationale", "当前保持日常复核。", "route", "/dashboard")));
    }

    private JsonNode postJson(String path, String token, String body, String idempotencyKey) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        String response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).path("data");
    }

    private JsonNode getData(String path, String token) throws Exception {
        String response = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).path("data");
    }

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = json.readTree(body);
        return root.path("data").path("accessToken").asText();
    }
}
