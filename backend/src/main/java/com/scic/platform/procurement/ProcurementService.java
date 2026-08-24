package com.scic.platform.procurement;

import com.scic.platform.common.BusinessException;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import com.scic.platform.system.AuditService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProcurementService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ProcurementService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Map<String, Object>> demands() {
        return jdbc.queryForList("select d.id,d.demand_no,m.material_code,m.material_name,m.unit,d.quantity,d.expected_date,d.priority,d.source_type,d.source_ref,d.status,d.notes,d.version,d.created_at from purchase_demand d join material m on m.id=d.material_id order by d.created_at desc");
    }

    @Transactional
    public Map<String, Object> createDemand(String requestId, DemandInput input, String sourceType, String sourceRef) {
        AuthUser user = SecuritySupport.currentUser();
        Long materialId = materialId(input.materialCode());
        String demandNo = number("REQ");
        jdbc.update("insert into purchase_demand(demand_no,material_id,quantity,expected_date,priority,source_type,source_ref,status,notes,created_by) values(?,?,?,?,?,?,?,?,?,?)",
                demandNo, materialId, input.quantity(), input.expectedDate(), normalized(input.priority(), "NORMAL"),
                sourceType == null ? "MANUAL" : sourceType, sourceRef, "DRAFT", input.notes(), user.id());
        Long id = jdbc.queryForObject("select id from purchase_demand where demand_no=?", Long.class, demandNo);
        audit.log(requestId, "CREATE_DEMAND", "PURCHASE_DEMAND", id, null, "DRAFT", demandNo);
        return jdbc.queryForMap("select d.id,d.demand_no,m.material_code,m.material_name,d.quantity,d.expected_date,d.priority,d.source_type,d.status,d.notes,d.version from purchase_demand d join material m on m.id=d.material_id where d.id=?", id);
    }

    public List<Map<String, Object>> plans() {
        return jdbc.queryForList("select p.id,p.plan_no,p.plan_name,p.status,p.total_amount,p.rejection_reason,p.version,p.created_at,p.updated_at,u.display_name created_by_name,au.display_name approved_by_name,(select count(*) from purchase_plan_item pi where pi.plan_id=p.id) item_count from purchase_plan p join sys_user u on u.id=p.created_by left join sys_user au on au.id=p.approved_by order by p.created_at desc");
    }

    public Map<String, Object> plan(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select p.id,p.plan_no,p.plan_name,p.status,p.total_amount,p.rejection_reason,p.version,p.created_at,p.updated_at from purchase_plan p where p.id=?", id);
        if (rows.isEmpty()) throw notFound("采购计划不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("select pi.id,pi.demand_id,m.material_code,m.material_name,m.unit,s.supplier_code,s.supplier_name,pi.quantity,pi.unit_price,(pi.quantity*pi.unit_price) amount,pi.expected_date from purchase_plan_item pi join material m on m.id=pi.material_id left join supplier s on s.id=pi.supplier_id where pi.plan_id=? order by pi.id", id));
        return result;
    }

    @Transactional
    public Map<String, Object> createPlan(String requestId, PlanInput input) {
        AuthUser user = SecuritySupport.currentUser();
        List<String> suppliers = input.items().stream().map(PlanItemInput::supplierCode).filter(v -> v != null && !v.isBlank()).distinct().toList();
        if (suppliers.size() != 1 || input.items().stream().anyMatch(i -> i.supplierCode() == null || i.supplierCode().isBlank())) {
            throw new BusinessException("BUSINESS_RULE_VIOLATION", "V1 每个采购计划必须且只能指定一个供应商", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String planNo = number("PLAN");
        BigDecimal total = input.items().stream().map(i -> {
            BigDecimal price = i.unitPrice() == null ? materialPrice(i.materialCode()) : i.unitPrice();
            return price.multiply(i.quantity());
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("insert into purchase_plan(plan_no,plan_name,status,total_amount,created_by) values(?,?,?,?,?)",
                planNo, input.planName(), "DRAFT", total, user.id());
        Long planId = jdbc.queryForObject("select id from purchase_plan where plan_no=?", Long.class, planNo);
        for (PlanItemInput item : input.items()) {
            Long materialId = materialId(item.materialCode());
            Long supplierId = item.supplierCode() == null || item.supplierCode().isBlank() ? null : supplierId(item.supplierCode());
            BigDecimal price = item.unitPrice() == null ? materialPrice(item.materialCode()) : item.unitPrice();
            if (item.demandId() != null) {
                Number demandCount = jdbc.queryForObject("select count(*) from purchase_demand where id=? and status='DRAFT'", Number.class, item.demandId());
                if (demandCount == null || demandCount.longValue() == 0) throw new BusinessException("INVALID_DEMAND", "采购需求不存在或已进入计划", HttpStatus.CONFLICT);
            }
            jdbc.update("insert into purchase_plan_item(plan_id,demand_id,material_id,supplier_id,quantity,unit_price,expected_date) values(?,?,?,?,?,?,?)",
                    planId, item.demandId(), materialId, supplierId, item.quantity(), price, item.expectedDate());
            if (item.demandId() != null) jdbc.update("update purchase_demand set status='PLANNED',version=version+1,updated_at=current_timestamp where id=?", item.demandId());
        }
        audit.log(requestId, "CREATE_PLAN", "PURCHASE_PLAN", planId, null, "DRAFT", planNo);
        return plan(planId);
    }

    @Transactional
    public Map<String, Object> transitionPlan(String requestId, long id, int expectedVersion, String rawAction, String reason) {
        AuthUser user = SecuritySupport.currentUser();
        Map<String, Object> row = one("select status,version from purchase_plan where id=?", id, "采购计划不存在");
        String before = row.get("status").toString();
        int version = ((Number) row.get("version")).intValue();
        if (version != expectedVersion) throw versionConflict(version);
        String action = rawAction.toUpperCase(Locale.ROOT);
        String after;
        switch (action) {
            case "SUBMIT" -> { requireRole(user, "BUYER"); requireState(before, "DRAFT"); after = "PENDING_APPROVAL"; }
            case "APPROVE" -> { requireRole(user, "MANAGER"); requireState(before, "PENDING_APPROVAL"); after = "APPROVED"; }
            case "REJECT" -> { requireRole(user, "MANAGER"); requireState(before, "PENDING_APPROVAL"); if (reason == null || reason.isBlank()) throw bad("驳回必须填写原因"); after = "REJECTED"; }
            case "CANCEL" -> { requireRole(user, "BUYER"); if (!List.of("DRAFT", "PENDING_APPROVAL").contains(before)) throw illegalState(before); after = "CANCELLED"; }
            default -> throw bad("未知计划动作: " + rawAction);
        }
        int updated = jdbc.update("update purchase_plan set status=?,rejection_reason=?,approved_by=?,approved_at=?,version=version+1,updated_at=current_timestamp where id=? and version=? and status=?",
                after, "REJECTED".equals(after) ? reason : null, "APPROVED".equals(after) ? user.id() : null,
                "APPROVED".equals(after) ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null, id, expectedVersion, before);
        if (updated != 1) throw versionConflict(expectedVersion + 1);
        audit.log(requestId, "PLAN_" + action, "PURCHASE_PLAN", id, before, after, reason);
        return plan(id);
    }

    @Transactional
    public List<Map<String, Object>> generateOrders(String requestId, long planId, int expectedVersion, String idempotencyKey) {
        requireRole(SecuritySupport.currentUser(), "BUYER");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new BusinessException("IDEMPOTENCY_REQUIRED", "生成订单必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        Map<String, Object> plan = one("select status,version from purchase_plan where id=?", planId, "采购计划不存在");
        String status = plan.get("status").toString();
        int version = ((Number) plan.get("version")).intValue();
        List<Map<String, Object>> existing = jdbc.queryForList("select o.id,o.order_no,s.supplier_name,o.status,o.order_amount,o.expected_arrival_date,o.version from purchase_order o join supplier s on s.id=o.supplier_id where o.plan_id=? and o.idempotency_key like ? order by o.id", planId, idempotencyKey + "-%");
        if (!existing.isEmpty()) return existing;
        requireState(status, "APPROVED");
        if (version != expectedVersion) throw versionConflict(version);
        List<Map<String, Object>> items = jdbc.queryForList("select pi.id,pi.material_id,coalesce(pi.supplier_id,(select min(id) from supplier where status='ACTIVE')) supplier_id,pi.quantity,pi.unit_price,pi.expected_date from purchase_plan_item pi where pi.plan_id=?", planId);
        if (items.isEmpty()) throw bad("采购计划没有明细");
        Map<Long, List<Map<String, Object>>> groups = items.stream().collect(Collectors.groupingBy(i -> ((Number) i.get("supplier_id")).longValue(), LinkedHashMap::new, Collectors.toList()));
        if (groups.size() != 1) throw new BusinessException("BUSINESS_RULE_VIOLATION", "V1 每个采购计划只能生成一个供应商订单", HttpStatus.UNPROCESSABLE_ENTITY);
        List<Map<String, Object>> created = new ArrayList<>();
        int index = 0;
        for (Map.Entry<Long, List<Map<String, Object>>> entry : groups.entrySet()) {
            long supplierId = entry.getKey();
            List<Map<String, Object>> supplierItems = entry.getValue();
            BigDecimal amount = supplierItems.stream().map(i -> decimal(i.get("quantity")).multiply(decimal(i.get("unit_price")))).reduce(BigDecimal.ZERO, BigDecimal::add);
            LocalDate expected = supplierItems.stream().map(i -> ((java.sql.Date) i.get("expected_date")).toLocalDate()).max(LocalDate::compareTo).orElse(LocalDate.now().plusDays(7));
            String orderNo = number("PO");
            String key = idempotencyKey + "-" + (++index);
            jdbc.update("insert into purchase_order(order_no,plan_id,supplier_id,status,order_amount,expected_arrival_date,idempotency_key,created_by) values(?,?,?,?,?,?,?,?)",
                    orderNo, planId, supplierId, "PENDING_CONFIRMATION", amount, expected, key, SecuritySupport.currentUser().id());
            Long orderId = jdbc.queryForObject("select id from purchase_order where order_no=?", Long.class, orderNo);
            for (Map<String, Object> item : supplierItems) {
                BigDecimal qty = decimal(item.get("quantity"));
                BigDecimal price = decimal(item.get("unit_price"));
                jdbc.update("insert into purchase_order_item(order_id,material_id,quantity,unit_price,amount) values(?,?,?,?,?)", orderId, item.get("material_id"), qty, price, qty.multiply(price));
            }
            audit.log(requestId, "GENERATE_ORDER", "PURCHASE_ORDER", orderId, null, "PENDING_CONFIRMATION", orderNo);
            created.add(order(orderId));
        }
        int updated = jdbc.update("update purchase_plan set status='ORDER_CREATED',version=version+1,updated_at=current_timestamp where id=? and version=? and status='APPROVED'", planId, expectedVersion);
        if (updated != 1) throw versionConflict(expectedVersion + 1);
        audit.log(requestId, "PLAN_ORDER_CREATED", "PURCHASE_PLAN", planId, "APPROVED", "ORDER_CREATED", idempotencyKey);
        return created;
    }

    public List<Map<String, Object>> orders() {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select o.id,o.order_no,o.plan_id,p.plan_no,s.supplier_code,s.supplier_name,o.status,o.order_amount,o.expected_arrival_date,o.version,o.confirmed_at,o.created_at,o.updated_at,(select count(*) from purchase_order_item oi where oi.order_id=o.id) item_count from purchase_order o join purchase_plan p on p.id=o.plan_id join supplier s on s.id=o.supplier_id";
        if ("SUPPLIER".equals(user.role())) return jdbc.queryForList(sql + " where o.supplier_id=? order by o.updated_at desc", user.supplierId());
        return jdbc.queryForList(sql + " order by o.updated_at desc");
    }

    public Map<String, Object> order(long id) {
        AuthUser user = SecuritySupport.currentUser();
        String base = "select o.id,o.order_no,o.plan_id,p.plan_no,s.id supplier_id,s.supplier_code,s.supplier_name,o.status,o.order_amount,o.expected_arrival_date,o.cancel_reason,o.version,o.confirmed_at,o.created_at,o.updated_at from purchase_order o join purchase_plan p on p.id=o.plan_id join supplier s on s.id=o.supplier_id where o.id=?";
        List<Map<String, Object>> rows = "SUPPLIER".equals(user.role()) ? jdbc.queryForList(base + " and o.supplier_id=?", id, user.supplierId()) : jdbc.queryForList(base, id);
        if (rows.isEmpty()) throw notFound("采购订单不存在或无权访问");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("select oi.id,m.material_code,m.material_name,m.unit,oi.quantity,oi.received_qty,oi.unit_price,oi.amount from purchase_order_item oi join material m on m.id=oi.material_id where oi.order_id=? order by oi.id", id));
        result.put("deliveryNotices", jdbc.queryForList("select id,notice_no,status,expected_arrival_at,carrier_name,tracking_no,exception_note,version,created_at from delivery_notice where order_id=? order by id", id));
        result.put("reconciliation", jdbc.queryForList("select id,reconciliation_no,status,order_amount,received_amount,difference_amount,dispute_reason,resolution_note,buyer_confirmed,supplier_confirmed,version,updated_at from reconciliation where order_id=?", id));
        return result;
    }

    @Transactional
    public Map<String, Object> transitionOrder(String requestId, long id, int expectedVersion, String rawAction, String note) {
        AuthUser user = SecuritySupport.currentUser();
        Map<String, Object> row = one("select status,version,supplier_id from purchase_order where id=?", id, "采购订单不存在");
        if ("SUPPLIER".equals(user.role()) && ((Number) row.get("supplier_id")).longValue() != user.supplierId()) throw new BusinessException("SUPPLIER_SCOPE_VIOLATION", "不能访问其他供应商订单", HttpStatus.FORBIDDEN);
        String before = row.get("status").toString();
        int version = ((Number) row.get("version")).intValue();
        if (version != expectedVersion) throw versionConflict(version);
        String action = rawAction.toUpperCase(Locale.ROOT);
        String after;
        switch (action) {
            case "CONFIRM" -> { requireRole(user, "SUPPLIER"); requireState(before, "PENDING_CONFIRMATION"); after = "CONFIRMED"; }
            case "REJECT" -> { requireRole(user, "SUPPLIER"); requireState(before, "PENDING_CONFIRMATION"); if (note == null || note.isBlank()) throw bad("拒绝订单必须填写原因"); after = "REJECTED"; }
            case "PREPARE_SHIPMENT" -> { requireRole(user, "SUPPLIER"); requireState(before, "CONFIRMED"); after = "PENDING_SHIPMENT"; }
            case "SHIP" -> { requireRole(user, "SUPPLIER"); requireState(before, "PENDING_SHIPMENT"); after = "SHIPPED"; }
            case "MARK_ARRIVED" -> { requireRole(user, "BUYER"); requireState(before, "SHIPPED"); after = "ARRIVED"; }
            case "START_RECONCILIATION" -> { requireRole(user, "BUYER"); requireState(before, "RECEIVED"); after = "RECONCILING"; }
            case "CANCEL" -> { requireRole(user, "BUYER"); if (!List.of("PENDING_CONFIRMATION", "CONFIRMED", "PENDING_SHIPMENT").contains(before)) throw illegalState(before); if (note == null || note.isBlank()) throw bad("取消订单必须填写原因"); after = "CANCELLED"; }
            default -> throw bad("未知订单动作: " + rawAction);
        }
        int updated = jdbc.update("update purchase_order set status=?,cancel_reason=?,confirmed_at=?,version=version+1,updated_at=current_timestamp where id=? and version=? and status=?",
                after, "CANCELLED".equals(after) || "REJECTED".equals(after) ? note : null,
                "CONFIRMED".equals(after) ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null,
                id, expectedVersion, before);
        if (updated != 1) throw versionConflict(expectedVersion + 1);
        audit.log(requestId, "ORDER_" + action, "PURCHASE_ORDER", id, before, after, note);
        return order(id);
    }

    private Long materialId(String code) {
        List<Long> ids = jdbc.query("select id from material where material_code=? and status='ACTIVE'", (rs,n) -> rs.getLong(1), code);
        if (ids.isEmpty()) throw new BusinessException("MATERIAL_NOT_FOUND", "物料不存在或已停用: " + code, HttpStatus.UNPROCESSABLE_ENTITY);
        return ids.get(0);
    }

    private Long supplierId(String code) {
        List<Long> ids = jdbc.query("select id from supplier where supplier_code=? and status='ACTIVE'", (rs,n) -> rs.getLong(1), code);
        if (ids.isEmpty()) throw new BusinessException("SUPPLIER_NOT_FOUND", "供应商不存在或已停用: " + code, HttpStatus.UNPROCESSABLE_ENTITY);
        return ids.get(0);
    }

    private BigDecimal materialPrice(String code) {
        List<BigDecimal> prices = jdbc.query("select standard_price from material where material_code=? and status='ACTIVE'", (rs,n) -> rs.getBigDecimal(1), code);
        if (prices.isEmpty()) throw new BusinessException("MATERIAL_NOT_FOUND", "物料不存在或已停用: " + code, HttpStatus.UNPROCESSABLE_ENTITY);
        return prices.get(0);
    }

    private Map<String, Object> one(String sql, long id, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw notFound(message);
        return rows.get(0);
    }

    private static void requireRole(AuthUser user, String role) {
        if (!role.equals(user.role())) throw new BusinessException("FORBIDDEN", "当前角色不能执行该动作", HttpStatus.FORBIDDEN);
    }

    private static void requireState(String actual, String expected) {
        if (!expected.equals(actual)) throw illegalState(actual);
    }

    private static BusinessException versionConflict(int actual) {
        return new BusinessException("VERSION_CONFLICT", "数据已被其他操作更新，请刷新后重试", HttpStatus.CONFLICT, Map.of("currentVersion", actual));
    }

    private static BusinessException illegalState(String state) {
        return new BusinessException("ILLEGAL_STATE_TRANSITION", "当前状态不允许执行该动作", HttpStatus.CONFLICT, Map.of("currentState", state));
    }

    private static BusinessException notFound(String message) { return new BusinessException("RESOURCE_NOT_FOUND", message, HttpStatus.NOT_FOUND); }
    private static BusinessException bad(String message) { return new BusinessException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST); }
    private static String number(String prefix) { return prefix + "-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(); }
    private static String normalized(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT); }
    private static BigDecimal decimal(Object value) { return value instanceof BigDecimal b ? b : new BigDecimal(value.toString()); }

    public record DemandInput(String materialCode, BigDecimal quantity, LocalDate expectedDate, String priority, String notes) {}
    public record PlanItemInput(Long demandId, String materialCode, String supplierCode, BigDecimal quantity, BigDecimal unitPrice, LocalDate expectedDate) {}
    public record PlanInput(String planName, List<PlanItemInput> items) {}
}
