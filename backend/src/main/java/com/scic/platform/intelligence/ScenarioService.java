package com.scic.platform.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.common.BusinessException;
import com.scic.platform.procurement.ProcurementService;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import com.scic.platform.system.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ScenarioService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProcurementService procurement;
    private final AuditService audit;

    public ScenarioService(JdbcTemplate jdbc, ObjectMapper json, ProcurementService procurement, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.procurement = procurement;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> simulate(String requestId, String idempotencyKey, SimulationInput input) {
        AuthUser user = SecuritySupport.currentUser();
        if (!List.of("BUYER", "MANAGER").contains(user.role())) throw forbidden("当前角色不能创建采购情景推演");
        String simulationKey = normalizeIdempotencyKey(idempotencyKey, "情景推演必须提供 Idempotency-Key");
        jdbc.queryForObject("select id from sys_user where id=? for update", Long.class, user.id());
        List<Map<String, Object>> replay = jdbc.queryForList(
                "select id from procurement_scenario where created_by=? and simulation_idempotency_key=?",
                user.id(), simulationKey);
        if (!replay.isEmpty()) return scenario(((Number) replay.get(0).get("id")).longValue());

        Map<String, Object> material = one(
                "select id,material_code,material_name,unit,safety_stock,min_order_qty,pack_size,lead_time_days,standard_price,version from material where material_code=? and status='ACTIVE'",
                input.materialCode(), "物料不存在或已停用");
        long materialId = number(material.get("id")).longValue();
        Map<String, Object> run = selectForecastRun(materialId, input.forecastRunId());
        long runId = number(run.get("id")).longValue();
        List<Map<String, Object>> sequence = jdbc.queryForList(
                "select forecast_date,predicted_qty from forecast_result where run_id=? order by forecast_date", runId);
        if (sequence.isEmpty()) {
            throw new BusinessException("FORECAST_RESULT_REQUIRED", "选中的预测批次没有逐日结果，不能进行情景推演", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Map<String, Object> inventory = jdbc.queryForMap(
                "select coalesce(sum(on_hand_qty),0) on_hand_qty,coalesce(sum(reserved_qty),0) reserved_qty,coalesce(sum(in_transit_qty),0) in_transit_qty,max(updated_at) inventory_updated_at from inventory where material_id=?",
                materialId);
        List<Map<String, Object>> affectedOrders = affectedOrders(materialId, input.supplierDelayDays());
        Map<String, Object> parameters = parameters(input);
        Map<String, Object> sourceSnapshot = sourceSnapshot(material, run, inventory, affectedOrders);

        Projection baseline = project(material, inventory, sequence, BigDecimal.ZERO, 0, BigDecimal.ZERO, ONE_HUNDRED, BigDecimal.ZERO);
        Projection simulated = project(material, inventory, sequence, input.demandChangePercent(), input.supplierDelayDays(),
                input.safetyStockChangePercent(), input.qualificationRatePercent(), input.priceChangePercent());

        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("demandQty", scaled(simulated.totalDemand().subtract(baseline.totalDemand())));
        deltas.put("recommendedOrderQty", scaled(simulated.recommendedOrderQty().subtract(baseline.recommendedOrderQty())));
        deltas.put("estimatedAmount", money(simulated.estimatedAmount().subtract(baseline.estimatedAmount())));
        deltas.put("riskDateShiftDays", riskDateShift(baseline.riskDate(), simulated.riskDate()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("material", materialView(material));
        result.put("forecast", forecastView(run));
        result.put("parameters", parameters);
        result.put("sourceSnapshot", sourceSnapshot);
        result.put("baseline", baseline.toMap());
        result.put("simulated", simulated.toMap());
        result.put("deltas", deltas);
        result.put("affectedOrders", affectedOrders);
        result.put("assumptions", List.of(
                "预测序列取自已成功保存的14日预测批次，不由大模型临时生成。",
                "在途数量按物料提前期到达；供应延迟会整体后移到达日，超过窗口时不计入窗口供给。",
                "到货合格率仅折算在途有效数量；采购建议按最小起订量和包装量向上取整。",
                "推演只生成两小时有效的预览记录，确认前不会修改库存、订单或采购需求。"));

        String fingerprint = sha256(str(run.get("data_hash")) + write(parameters) + write(sourceSnapshot));
        String scenarioNo = nextNo("SCN");
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(2);
        jdbc.update("insert into procurement_scenario(scenario_no,scenario_name,material_id,forecast_run_id,parameters_json,input_snapshot,result_snapshot,data_fingerprint,baseline_order_qty,simulated_order_qty,simulated_amount,risk_level,status,simulation_idempotency_key,created_by,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                scenarioNo, input.scenarioName().trim(), materialId, runId, write(parameters), write(sourceSnapshot), write(result), fingerprint,
                baseline.recommendedOrderQty(), simulated.recommendedOrderQty(), simulated.estimatedAmount(), simulated.riskLevel(), "PREVIEW",
                simulationKey, user.id(), Timestamp.from(expiresAt.toInstant()));
        Long id = jdbc.queryForObject("select id from procurement_scenario where scenario_no=?", Long.class, scenarioNo);
        audit.log(requestId, "SIMULATE_PROCUREMENT_SCENARIO", "PROCUREMENT_SCENARIO", id, null, "PREVIEW",
                material.get("material_code") + ";risk=" + simulated.riskLevel() + ";suggestion=" + simulated.recommendedOrderQty());
        return scenario(id == null ? 0L : id);
    }

    public List<Map<String, Object>> scenarios() {
        AuthUser user = SecuritySupport.currentUser();
        String select = "select ps.id,ps.scenario_no,ps.scenario_name,m.material_code,m.material_name,fr.run_no,ps.baseline_order_qty,ps.simulated_order_qty,ps.simulated_amount,ps.risk_level,case when ps.status='PREVIEW' and ps.expires_at<current_timestamp then 'EXPIRED' else ps.status end status,ps.version,ps.expires_at,ps.adopted_at,ps.adopted_demand_id,ps.created_at,u.display_name created_by_name from procurement_scenario ps join material m on m.id=ps.material_id join forecast_run fr on fr.id=ps.forecast_run_id join sys_user u on u.id=ps.created_by";
        if ("MANAGER".equals(user.role())) return jdbc.queryForList(select + " order by ps.id desc");
        return jdbc.queryForList(select + " where ps.created_by=? order by ps.id desc", user.id());
    }

    public Map<String, Object> scenario(long id) {
        Map<String, Object> row = visibleScenario(id, false);
        Map<String, Object> out = new LinkedHashMap<>(readMap(row.get("result_snapshot")));
        out.put("id", row.get("id"));
        out.put("scenarioNo", row.get("scenario_no"));
        out.put("scenarioName", row.get("scenario_name"));
        out.put("status", effectiveStatus(row));
        out.put("version", row.get("version"));
        out.put("dataFingerprint", row.get("data_fingerprint"));
        out.put("expiresAt", row.get("expires_at"));
        out.put("createdAt", row.get("created_at"));
        out.put("adoptedAt", row.get("adopted_at"));
        if (row.get("adopted_demand_id") != null) {
            List<Map<String, Object>> demand = jdbc.queryForList(
                    "select d.id,d.demand_no,d.quantity,d.expected_date,d.priority,d.status,d.source_type,d.source_ref,d.notes from purchase_demand d where d.id=?",
                    row.get("adopted_demand_id"));
            out.put("adoptedDemand", demand.isEmpty() ? null : demand.get(0));
        } else out.put("adoptedDemand", null);
        return out;
    }

    @Transactional(noRollbackFor = ScenarioExpiredException.class)
    public Map<String, Object> adopt(String requestId, long id, String idempotencyKey, int expectedVersion,
                                     LocalDate expectedDate, String priority, String note) {
        AuthUser user = SecuritySupport.currentUser();
        if (!"BUYER".equals(user.role())) throw forbidden("只有采购协同人员可确认情景并生成采购需求");
        String adoptionKey = normalizeIdempotencyKey(idempotencyKey, "确认情景必须提供 Idempotency-Key");
        Map<String, Object> row = visibleScenario(id, true);
        if ("ADOPTED".equals(row.get("status"))) {
            if (adoptionKey.equals(row.get("adoption_idempotency_key"))) return scenario(id);
            throw new BusinessException("SCENARIO_ALREADY_ADOPTED", "该情景已经生成采购需求", HttpStatus.CONFLICT);
        }
        if ("EXPIRED".equals(effectiveStatus(row))) {
            int expired = jdbc.update("update procurement_scenario set status='EXPIRED',version=version+1 where id=? and status='PREVIEW'", id);
            if (expired == 1) {
                audit.log(requestId, "EXPIRE_PROCUREMENT_SCENARIO", "PROCUREMENT_SCENARIO", id, "PREVIEW", "EXPIRED", "expired before adoption");
            }
            throw new ScenarioExpiredException();
        }
        if (((Number) row.get("version")).intValue() != expectedVersion) throw conflict("情景版本已变化，请刷新后重试");
        if (!"PREVIEW".equals(row.get("status"))) throw conflict("当前情景不能确认");
        String normalizedPriority = priority == null ? "NORMAL" : priority.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "HIGH", "URGENT").contains(normalizedPriority)) {
            throw new BusinessException("VALIDATION_ERROR", "优先级仅支持 NORMAL、HIGH、URGENT", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> result = readMap(row.get("result_snapshot"));
        Map<String, Object> simulated = map(result.get("simulated"));
        BigDecimal quantity = decimal(simulated.get("recommendedOrderQty"));
        if (quantity.signum() <= 0) {
            throw new BusinessException("NO_PURCHASE_SUGGESTION", "当前模拟结果无需新增采购，不能生成采购需求", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String materialCode = jdbc.queryForObject("select material_code from material where id=?", String.class, row.get("material_id"));
        String businessNote = "情景推演确认：" + row.get("scenario_name") + "；场景编号=" + row.get("scenario_no");
        if (note != null && !note.isBlank()) businessNote += "；" + note.trim();
        if (businessNote.length() > 500) businessNote = businessNote.substring(0, 500);
        Map<String, Object> demand = procurement.createDemand(requestId,
                new ProcurementService.DemandInput(materialCode, quantity, expectedDate, normalizedPriority, businessNote),
                "SCENARIO", row.get("scenario_no").toString());
        long demandId = number(demand.get("id")).longValue();
        int updated = jdbc.update("update procurement_scenario set status='ADOPTED',version=version+1,adoption_idempotency_key=?,adopted_demand_id=?,adopted_at=current_timestamp where id=? and version=? and status='PREVIEW'",
                adoptionKey, demandId, id, expectedVersion);
        if (updated != 1) throw conflict("情景已由其他请求处理");
        audit.log(requestId, "ADOPT_PROCUREMENT_SCENARIO", "PROCUREMENT_SCENARIO", id, "PREVIEW", "ADOPTED", "purchaseDemandId=" + demandId);
        return scenario(id);
    }

    private Map<String, Object> selectForecastRun(long materialId, Long requestedRunId) {
        String sql = "select id,run_no,as_of_date,horizon_days,model_name,model_version,data_hash,data_label,status,created_at from forecast_run where material_id=? and status in ('SUCCEEDED','COMPLETED')";
        List<Map<String, Object>> rows = requestedRunId == null
                ? jdbc.queryForList(sql + " order by id desc limit 1", materialId)
                : jdbc.queryForList(sql + " and id=?", materialId, requestedRunId);
        if (rows.isEmpty()) throw new BusinessException("FORECAST_REQUIRED", "该物料没有可用的成功预测批次，请先运行14天需求预测", HttpStatus.UNPROCESSABLE_ENTITY);
        return rows.get(0);
    }

    private List<Map<String, Object>> affectedOrders(long materialId, int delayDays) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.id,o.order_no,o.status,o.expected_arrival_date,s.supplier_name,sum(oi.quantity-oi.received_qty) remaining_qty from purchase_order o join purchase_order_item oi on oi.order_id=o.id join supplier s on s.id=o.supplier_id where oi.material_id=? and o.status in ('PENDING_CONFIRMATION','CONFIRMED','PENDING_SHIPMENT','SHIPPED') group by o.id,o.order_no,o.status,o.expected_arrival_date,s.supplier_name having sum(oi.quantity-oi.received_qty)>0 order by o.expected_arrival_date,o.id",
                materialId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("orderNo", row.get("order_no"));
            item.put("status", row.get("status"));
            item.put("supplierName", row.get("supplier_name"));
            item.put("remainingQty", scaled(decimal(row.get("remaining_qty"))));
            LocalDate original = row.get("expected_arrival_date") == null ? null : toDate(row.get("expected_arrival_date"));
            item.put("expectedArrivalDate", original == null ? null : original.toString());
            item.put("simulatedArrivalDate", original == null ? null : original.plusDays(delayDays).toString());
            result.add(item);
        }
        return result;
    }

    private Projection project(Map<String, Object> material, Map<String, Object> inventory, List<Map<String, Object>> sequence,
                               BigDecimal demandChangePercent, int supplierDelayDays, BigDecimal safetyStockChangePercent,
                               BigDecimal qualificationRatePercent, BigDecimal priceChangePercent) {
        BigDecimal demandFactor = BigDecimal.ONE.add(demandChangePercent.divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal safetyFactor = BigDecimal.ONE.add(safetyStockChangePercent.divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal qualificationRate = qualificationRatePercent.divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP);
        BigDecimal priceFactor = BigDecimal.ONE.add(priceChangePercent.divide(ONE_HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal safetyStock = decimal(material.get("safety_stock")).multiply(safetyFactor).max(BigDecimal.ZERO);
        BigDecimal availableNow = decimal(inventory.get("on_hand_qty")).subtract(decimal(inventory.get("reserved_qty"))).max(BigDecimal.ZERO);
        BigDecimal inTransit = decimal(inventory.get("in_transit_qty")).multiply(qualificationRate).max(BigDecimal.ZERO);
        int arrivalIndex = Math.max(1, number(material.get("lead_time_days")).intValue() + supplierDelayDays);
        boolean arrivesWithinWindow = arrivalIndex <= sequence.size();
        BigDecimal effectiveSupply = availableNow.add(arrivesWithinWindow ? inTransit : BigDecimal.ZERO);
        BigDecimal balance = availableNow;
        BigDecimal totalDemand = BigDecimal.ZERO;
        BigDecimal maxRiskQty = BigDecimal.ZERO;
        String riskDate = null;
        String stockoutDate = null;
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            Map<String, Object> point = sequence.get(i);
            LocalDate date = toDate(point.get("forecast_date"));
            BigDecimal arriving = i + 1 == arrivalIndex ? inTransit : BigDecimal.ZERO;
            balance = balance.add(arriving);
            BigDecimal demand = decimal(point.get("predicted_qty")).multiply(demandFactor).max(BigDecimal.ZERO);
            totalDemand = totalDemand.add(demand);
            balance = balance.subtract(demand);
            BigDecimal riskQty = safetyStock.subtract(balance).max(BigDecimal.ZERO);
            if (riskDate == null && riskQty.signum() > 0) riskDate = date.toString();
            if (stockoutDate == null && balance.signum() < 0) stockoutDate = date.toString();
            maxRiskQty = maxRiskQty.max(riskQty);
            Map<String, Object> projected = new LinkedHashMap<>();
            projected.put("date", date.toString());
            projected.put("demandQty", scaled(demand));
            projected.put("arrivalQty", scaled(arriving));
            projected.put("projectedAvailableQty", scaled(balance));
            projected.put("safetyStock", scaled(safetyStock));
            projected.put("riskQty", scaled(riskQty));
            points.add(projected);
        }
        BigDecimal need = totalDemand.add(safetyStock).subtract(effectiveSupply).max(BigDecimal.ZERO);
        BigDecimal recommended = roundOrder(need, decimal(material.get("min_order_qty")), decimal(material.get("pack_size")));
        BigDecimal adjustedPrice = decimal(material.get("standard_price")).multiply(priceFactor).max(BigDecimal.ZERO);
        BigDecimal amount = recommended.multiply(adjustedPrice);
        String riskLevel = stockoutDate != null ? "CRITICAL" : riskDate != null ? "HIGH" : recommended.signum() > 0 ? "MEDIUM" : "LOW";
        return new Projection(scaled(totalDemand), scaled(safetyStock), scaled(availableNow), scaled(inTransit), arrivalIndex,
                arrivesWithinWindow, scaled(effectiveSupply), scaled(recommended), money(amount), riskLevel, riskDate, stockoutDate,
                scaled(balance), scaled(maxRiskQty), points);
    }

    private Map<String, Object> parameters(SimulationInput input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demandChangePercent", input.demandChangePercent());
        out.put("supplierDelayDays", input.supplierDelayDays());
        out.put("safetyStockChangePercent", input.safetyStockChangePercent());
        out.put("qualificationRatePercent", input.qualificationRatePercent());
        out.put("priceChangePercent", input.priceChangePercent());
        return out;
    }

    private Map<String, Object> sourceSnapshot(Map<String, Object> material, Map<String, Object> run,
                                               Map<String, Object> inventory, List<Map<String, Object>> orders) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("onHandQty", scaled(decimal(inventory.get("on_hand_qty"))));
        out.put("reservedQty", scaled(decimal(inventory.get("reserved_qty"))));
        out.put("inTransitQty", scaled(decimal(inventory.get("in_transit_qty"))));
        out.put("availableNowQty", scaled(decimal(inventory.get("on_hand_qty")).subtract(decimal(inventory.get("reserved_qty"))).max(BigDecimal.ZERO)));
        out.put("inventoryUpdatedAt", inventory.get("inventory_updated_at"));
        out.put("affectedOrderCount", orders.size());
        out.put("forecastRunNo", run.get("run_no"));
        out.put("forecastDataHash", run.get("data_hash"));
        out.put("materialVersion", material.get("version"));
        return out;
    }

    private static Map<String, Object> materialView(Map<String, Object> material) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", material.get("id"));
        out.put("code", material.get("material_code"));
        out.put("name", material.get("material_name"));
        out.put("unit", material.get("unit"));
        out.put("leadTimeDays", material.get("lead_time_days"));
        out.put("safetyStock", material.get("safety_stock"));
        out.put("minOrderQty", material.get("min_order_qty"));
        out.put("packSize", material.get("pack_size"));
        out.put("standardPrice", material.get("standard_price"));
        return out;
    }

    private static Map<String, Object> forecastView(Map<String, Object> run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.get("id"));
        out.put("runNo", run.get("run_no"));
        out.put("asOfDate", run.get("as_of_date"));
        out.put("horizonDays", run.get("horizon_days"));
        out.put("modelName", run.get("model_name"));
        out.put("modelVersion", run.get("model_version"));
        out.put("dataLabel", run.get("data_label"));
        return out;
    }

    private Map<String, Object> visibleScenario(long id, boolean forUpdate) {
        AuthUser user = SecuritySupport.currentUser();
        String suffix = forUpdate ? " for update" : "";
        List<Map<String, Object>> rows = "MANAGER".equals(user.role())
                ? jdbc.queryForList("select * from procurement_scenario where id=?" + suffix, id)
                : jdbc.queryForList("select * from procurement_scenario where id=? and created_by=?" + suffix, id, user.id());
        if (rows.isEmpty()) throw notFound("采购情景不存在或不在当前可见范围");
        return rows.get(0);
    }

    private static String effectiveStatus(Map<String, Object> row) {
        if (!"PREVIEW".equals(row.get("status"))) return row.get("status").toString();
        Timestamp expires = (Timestamp) row.get("expires_at");
        return expires.toInstant().isBefore(OffsetDateTime.now(ZoneOffset.ofHours(8)).toInstant()) ? "EXPIRED" : "PREVIEW";
    }

    private static BigDecimal roundOrder(BigDecimal need, BigDecimal minimum, BigDecimal packSize) {
        if (need.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal pack = packSize.signum() > 0 ? packSize : BigDecimal.ONE;
        return need.max(minimum).divide(pack, 0, RoundingMode.CEILING).multiply(pack);
    }

    private static Long riskDateShift(String baseline, String simulated) {
        if (baseline == null || simulated == null) return null;
        return ChronoUnit.DAYS.between(LocalDate.parse(baseline), LocalDate.parse(simulated));
    }

    private Map<String, Object> one(String sql, Object arg, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arg);
        if (rows.isEmpty()) throw notFound(message);
        return rows.get(0);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private Map<String, Object> readMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        try { return json.readValue(value.toString(), new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("情景快照无法读取", error); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> candidate ? (Map<String, Object>) candidate : Map.of();
    }

    private static Number number(Object value) {
        if (value instanceof Number number) return number;
        return new BigDecimal(value.toString());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static BigDecimal scaled(BigDecimal value) { return value.setScale(4, RoundingMode.HALF_UP); }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static LocalDate toDate(Object value) { return value instanceof java.sql.Date date ? date.toLocalDate() : LocalDate.parse(value.toString()); }
    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private static String normalizeIdempotencyKey(String value, String requiredMessage) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_REQUIRED", requiredMessage, HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > 80) {
            throw new BusinessException("VALIDATION_ERROR", "Idempotency-Key 最长为80个字符", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }
    private static String nextNo(String prefix) { return prefix + "-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(Locale.ROOT); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static BusinessException notFound(String message) { return new BusinessException("RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND); }
    private static BusinessException forbidden(String message) { return new BusinessException("FORBIDDEN", message, HttpStatus.FORBIDDEN); }
    private static BusinessException conflict(String message) { return new BusinessException("VERSION_CONFLICT", message, HttpStatus.CONFLICT); }

    private static final class ScenarioExpiredException extends BusinessException {
        private ScenarioExpiredException() {
            super("SCENARIO_EXPIRED", "情景预览已超过两小时，请重新推演", HttpStatus.CONFLICT);
        }
    }

    public record SimulationInput(String scenarioName, String materialCode, Long forecastRunId,
                                  BigDecimal demandChangePercent, int supplierDelayDays,
                                  BigDecimal safetyStockChangePercent, BigDecimal qualificationRatePercent,
                                  BigDecimal priceChangePercent) {}

    private record Projection(BigDecimal totalDemand, BigDecimal safetyStock, BigDecimal availableNow,
                              BigDecimal effectiveInTransit, int inTransitArrivalDay, boolean inTransitWithinWindow,
                              BigDecimal effectiveSupply, BigDecimal recommendedOrderQty, BigDecimal estimatedAmount,
                              String riskLevel, String riskDate, String stockoutDate, BigDecimal endAvailableQty,
                              BigDecimal maxRiskQty, List<Map<String, Object>> points) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("totalDemand", totalDemand);
            out.put("safetyStock", safetyStock);
            out.put("availableNow", availableNow);
            out.put("effectiveInTransit", effectiveInTransit);
            out.put("inTransitArrivalDay", inTransitArrivalDay);
            out.put("inTransitWithinWindow", inTransitWithinWindow);
            out.put("effectiveSupply", effectiveSupply);
            out.put("recommendedOrderQty", recommendedOrderQty);
            out.put("estimatedAmount", estimatedAmount);
            out.put("riskLevel", riskLevel);
            out.put("riskDate", riskDate);
            out.put("stockoutDate", stockoutDate);
            out.put("endAvailableQty", endAvailableQty);
            out.put("maxRiskQty", maxRiskQty);
            out.put("points", points);
            return out;
        }
    }
}
