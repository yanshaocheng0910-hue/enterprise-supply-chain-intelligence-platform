package com.scic.platform.dashboard;

import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkQueueService {
    private final JdbcTemplate jdbc;

    public WorkQueueService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> queue() {
        AuthUser user = SecuritySupport.currentUser();
        List<Map<String, Object>> tasks = new ArrayList<>();
        switch (user.role()) {
            case "BUYER" -> buyerTasks(tasks);
            case "MANAGER" -> managerTasks(tasks);
            case "SUPPLIER" -> supplierTasks(tasks, Objects.requireNonNull(user.supplierId()));
            default -> { }
        }
        tasks.sort(Comparator
                .comparingInt((Map<String, Object> item) -> severityRank(item.get("severity")))
                .thenComparing(item -> Objects.toString(item.get("due_at"), "9999-12-31"))
                .thenComparing(item -> Objects.toString(item.get("created_at"), ""), Comparator.reverseOrder()));
        if (tasks.size() > 100) tasks = new ArrayList<>(tasks.subList(0, 100));

        long urgent = tasks.stream().filter(item -> SetLike.high(item.get("severity"))).count();
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) byType.merge(task.get("task_type").toString(), 1L, Long::sum);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generated_at", java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        result.put("role", user.role());
        result.put("summary", Map.of("total", tasks.size(), "urgent", urgent, "by_type", byType));
        result.put("tasks", tasks);
        return result;
    }

    private void buyerTasks(List<Map<String, Object>> tasks) {
        addWarnings(tasks);
        jdbc.queryForList("select d.id,d.demand_no,m.material_name,d.quantity,d.expected_date,d.priority,d.status,d.created_at from purchase_demand d join material m on m.id=d.material_id where d.status in ('DRAFT','SUBMITTED') order by d.expected_date limit 25")
                .forEach(row -> tasks.add(task("DEMAND_PLANNING", prioritySeverity(row.get("priority")), "采购需求待编入计划",
                        row.get("demand_no") + " · " + row.get("material_name") + " · " + row.get("quantity"), "采购需求", row.get("demand_no"),
                        row.get("id"), row.get("expected_date"), row.get("created_at"), "/purchase-demands", "进入需求")));
        jdbc.queryForList("select id,plan_no,plan_name,status,total_amount,created_at from purchase_plan where status='DRAFT' order by created_at limit 20")
                .forEach(row -> tasks.add(task("PLAN_SUBMISSION", "MEDIUM", "采购计划待提交",
                        row.get("plan_no") + " · " + row.get("plan_name"), "采购计划", row.get("plan_no"), row.get("id"), null,
                        row.get("created_at"), "/purchase-plans", "进入计划")));
        addOverdueOrders(tasks, null);
        jdbc.queryForList("select id,order_no,status,expected_arrival_date,created_at from purchase_order where status in ('ARRIVED','PARTIALLY_RECEIVED') order by expected_arrival_date limit 20")
                .forEach(row -> tasks.add(task("RECEIPT_PENDING", "HIGH", "到货待收货验收",
                        row.get("order_no") + " 已到货，需核对合格与拒收数量", "采购订单", row.get("order_no"), row.get("id"),
                        row.get("expected_arrival_date"), row.get("created_at"), "/receipts", "进入收货")));
        jdbc.queryForList("select r.id,r.reconciliation_no,o.order_no,r.difference_amount,r.status,r.created_at from reconciliation r join purchase_order o on o.id=r.order_id where r.buyer_confirmed=false and r.status not in ('COMPLETED','CLOSED','RESOLVED') order by r.created_at limit 20")
                .forEach(row -> tasks.add(task("RECONCILIATION_CONFIRM", decimalSeverity(row.get("difference_amount")), "对账单待采购方确认",
                        row.get("reconciliation_no") + " · 订单 " + row.get("order_no") + " · 差异 " + row.get("difference_amount"), "对账单",
                        row.get("reconciliation_no"), row.get("id"), null, row.get("created_at"), "/reconciliation", "进入对账")));
    }

    private void managerTasks(List<Map<String, Object>> tasks) {
        addWarnings(tasks);
        jdbc.queryForList("select id,plan_no,plan_name,total_amount,created_at from purchase_plan where status='PENDING_APPROVAL' order by created_at limit 30")
                .forEach(row -> tasks.add(task("PLAN_APPROVAL", "HIGH", "采购计划待审批",
                        row.get("plan_no") + " · " + row.get("plan_name") + " · 金额 " + row.get("total_amount"), "采购计划", row.get("plan_no"),
                        row.get("id"), null, row.get("created_at"), "/purchase-plans", "进入审批")));
        addOverdueOrders(tasks, null);
        jdbc.queryForList("select r.id,r.reconciliation_no,o.order_no,r.difference_amount,r.status,r.created_at from reconciliation r join purchase_order o on o.id=r.order_id where r.status='DISPUTED' order by r.created_at limit 20")
                .forEach(row -> tasks.add(task("RECONCILIATION_DISPUTE", "HIGH", "对账争议待复核",
                        row.get("reconciliation_no") + " · 订单 " + row.get("order_no") + " · 差异 " + row.get("difference_amount"), "对账单",
                        row.get("reconciliation_no"), row.get("id"), null, row.get("created_at"), "/reconciliation", "进入复核")));
    }

    private void supplierTasks(List<Map<String, Object>> tasks, long supplierId) {
        jdbc.queryForList("select id,order_no,status,expected_arrival_date,created_at from purchase_order where supplier_id=? and status='PENDING_CONFIRMATION' order by expected_arrival_date limit 25", supplierId)
                .forEach(row -> tasks.add(task("ORDER_CONFIRMATION", "HIGH", "采购订单待确认",
                        row.get("order_no") + " · 请确认是否接受交期", "采购订单", row.get("order_no"), row.get("id"),
                        row.get("expected_arrival_date"), row.get("created_at"), "/orders", "确认订单")));
        jdbc.queryForList("select id,order_no,status,expected_arrival_date,created_at from purchase_order where supplier_id=? and status in ('CONFIRMED','PENDING_SHIPMENT') order by expected_arrival_date limit 25", supplierId)
                .forEach(row -> tasks.add(task("DELIVERY_NOTICE", "MEDIUM", "订单待提交交付通知",
                        row.get("order_no") + " · 当前状态 " + row.get("status"), "采购订单", row.get("order_no"), row.get("id"),
                        row.get("expected_arrival_date"), row.get("created_at"), "/deliveries", "进入交付")));
        addOverdueOrders(tasks, supplierId);
        jdbc.queryForList("select r.id,r.reconciliation_no,o.order_no,r.difference_amount,r.status,r.created_at from reconciliation r join purchase_order o on o.id=r.order_id where o.supplier_id=? and r.supplier_confirmed=false and r.status not in ('COMPLETED','CLOSED','RESOLVED') order by r.created_at limit 20", supplierId)
                .forEach(row -> tasks.add(task("RECONCILIATION_CONFIRM", decimalSeverity(row.get("difference_amount")), "对账单待供应商确认",
                        row.get("reconciliation_no") + " · 订单 " + row.get("order_no") + " · 差异 " + row.get("difference_amount"), "对账单",
                        row.get("reconciliation_no"), row.get("id"), null, row.get("created_at"), "/reconciliation", "进入对账")));
    }

    private void addWarnings(List<Map<String, Object>> tasks) {
        jdbc.queryForList("select id,warning_no,title,reason_text,severity,created_at from warning_record where status in ('OPEN','ACKNOWLEDGED') order by case severity when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end,created_at desc limit 30")
                .forEach(row -> tasks.add(task("WARNING_ACTION", Objects.toString(row.get("severity"), "MEDIUM"), Objects.toString(row.get("title"), "风险待处理"),
                        Objects.toString(row.get("reason_text"), "请进入预警中心核实"), "预警", row.get("warning_no"), row.get("id"), null,
                        row.get("created_at"), "/warnings", "进入预警")));
    }

    private void addOverdueOrders(List<Map<String, Object>> tasks, Long supplierId) {
        String sql = "select id,order_no,status,expected_arrival_date,created_at from purchase_order where expected_arrival_date<current_date and status not in ('COMPLETED','CANCELLED','REJECTED')";
        List<Map<String, Object>> rows = supplierId == null
                ? jdbc.queryForList(sql + " order by expected_arrival_date limit 25")
                : jdbc.queryForList(sql + " and supplier_id=? order by expected_arrival_date limit 25", supplierId);
        rows.forEach(row -> tasks.add(task("ORDER_OVERDUE", "HIGH", "订单已超过预计到货日",
                row.get("order_no") + " · 当前状态 " + row.get("status"), "采购订单", row.get("order_no"), row.get("id"),
                row.get("expected_arrival_date"), row.get("created_at"), "/orders", "核查订单")));
    }

    private static Map<String, Object> task(String type, String severity, String title, String description,
            String targetType, Object referenceNo, Object targetId, Object dueAt, Object createdAt, String route, String actionLabel) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("task_key", type + ":" + targetId);
        task.put("task_type", type);
        task.put("severity", severity);
        task.put("title", title);
        task.put("description", description);
        task.put("target_type", targetType);
        task.put("reference_no", referenceNo);
        task.put("target_id", targetId);
        task.put("due_at", dueAt);
        task.put("created_at", createdAt);
        task.put("route", route);
        task.put("action_label", actionLabel);
        return task;
    }

    private static String prioritySeverity(Object priority) { return "URGENT".equals(priority) ? "HIGH" : "MEDIUM"; }
    private static String decimalSeverity(Object value) { try { return new java.math.BigDecimal(Objects.toString(value, "0")).signum() == 0 ? "MEDIUM" : "HIGH"; } catch (Exception ignored) { return "MEDIUM"; } }
    private static int severityRank(Object value) { return switch (Objects.toString(value, "LOW")) { case "CRITICAL" -> 0; case "HIGH" -> 1; case "MEDIUM" -> 2; default -> 3; }; }
    private static final class SetLike { private SetLike() {} static boolean high(Object value) { return Set.of("CRITICAL", "HIGH").contains(Objects.toString(value, "")); } }
}
