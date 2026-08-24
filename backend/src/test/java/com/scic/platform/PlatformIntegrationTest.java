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
    @MockBean com.scic.platform.intelligence.AiGateway aiGateway;

    @BeforeEach
    void ensureSecondSupplierOrder() {
        when(aiGateway.parse(anyMap(), anyString())).thenReturn(Map.of(
                "provider", "rule",
                "original_text", "请采购 MAT-BOX-05 20 件",
                "fields", Map.of("material_code", "MAT-BOX-05", "quantity", 20),
                "missing_fields", List.of("required_date"),
                "warnings", List.of()));
        Integer users = jdbc.queryForObject("select count(*) from sys_user where username='supplier2'", Integer.class);
        if (users != null && users == 0) jdbc.update("insert into sys_user(username,display_name,password_hash,role_code,supplier_id) values('supplier2','供应商·林青','{noop}123456','SUPPLIER',2)");
        Integer orders = jdbc.queryForObject("select count(*) from purchase_order where order_no='PO-SCOPE-002'", Integer.class);
        if (orders != null && orders == 0) {
            jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values('PO-SCOPE-002',1,2,'PENDING_CONFIRMATION',100,'2026-09-10','test-scope-order',2)");
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
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test @Order(4)
    void buyerCanCreateDemandAndCrossSupplierPlanIsRejected() throws Exception {
        String buyer = token("buyer", "123456");
        mvc.perform(post("/api/v1/procurement/demands").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialCode\":\"MAT-SEN-02\",\"quantity\":120,\"expectedDate\":\"2026-09-20\",\"priority\":\"NORMAL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
        long before = jdbc.queryForObject("select count(*) from purchase_plan", Long.class);
        String body = "{\"planName\":\"错误的跨供应商计划\",\"items\":[" +
                "{\"materialCode\":\"MAT-SEN-02\",\"supplierCode\":\"SUP-001\",\"quantity\":10,\"expectedDate\":\"2026-09-20\"}," +
                "{\"materialCode\":\"MAT-ALU-03\",\"supplierCode\":\"SUP-002\",\"quantity\":10,\"expectedDate\":\"2026-09-20\"}]}";
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
        mvc.perform(post("/api/v1/procurement/demands").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialCode\":\"MAT-SEN-02\",\"quantity\":1,\"expectedDate\":\"2026-09-20\"}"))
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
