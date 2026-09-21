package com.scic.platform.procurement;

import com.scic.platform.common.BusinessException;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class OrderTimelineService {
    private final JdbcTemplate jdbc;

    public OrderTimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> timeline(long orderId) {
        AuthUser user = SecuritySupport.currentUser();
        String orderSql = "select o.id,o.order_no,o.status,o.supplier_id,s.supplier_name,o.expected_arrival_date,o.created_at,o.updated_at from purchase_order o join supplier s on s.id=o.supplier_id where o.id=?";
        List<Map<String, Object>> orders = "SUPPLIER".equals(user.role())
                ? jdbc.queryForList(orderSql + " and o.supplier_id=?", orderId, user.supplierId())
                : jdbc.queryForList(orderSql, orderId);
        if (orders.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "采购订单不存在或无权访问", HttpStatus.NOT_FOUND);

        Map<String, Object> order = orders.get(0);
        String orderNo = Objects.toString(order.get("order_no"), "");
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(event("ORDER:" + orderId + ":CREATED", "BUSINESS_RECORD", "ORDER", "订单已生成",
                "采购计划已转为执行订单", null, "PENDING_CONFIRMATION", null, null,
                "PURCHASE_ORDER", orderId, orderNo, order.get("created_at"), null));

        addAudit(events, """
                select l.*,o.order_no reference_no from operation_log l
                join purchase_order o on o.id=l.target_id
                where l.target_type='PURCHASE_ORDER' and o.id=?
                """, orderId);
        addAudit(events, """
                select l.*,n.notice_no reference_no from operation_log l
                join delivery_notice n on n.id=l.target_id
                where l.target_type='DELIVERY_NOTICE' and n.order_id=?
                """, orderId);
        addAudit(events, """
                select l.*,r.receipt_no reference_no from operation_log l
                join receipt r on r.id=l.target_id
                where l.target_type='RECEIPT' and r.order_id=?
                """, orderId);
        addAudit(events, """
                select l.*,r.reconciliation_no reference_no from operation_log l
                join reconciliation r on r.id=l.target_id
                where l.target_type='RECONCILIATION' and r.order_id=?
                """, orderId);

        jdbc.queryForList("""
                select t.id,t.transaction_no,t.quantity,t.before_qty,t.after_qty,t.created_at,m.material_name,w.warehouse_name,r.receipt_no
                from inventory_transaction t
                join receipt r on r.id=t.source_id and t.source_type='RECEIPT'
                join material m on m.id=t.material_id
                join warehouse w on w.id=t.warehouse_id
                where r.order_id=? order by t.created_at,t.id
                """, orderId).forEach(row -> events.add(event(
                        "INVENTORY:" + row.get("id"), "INVENTORY_TRANSACTION", "INVENTORY", "合格品已入库",
                        row.get("material_name") + " · " + row.get("quantity") + " · " + row.get("warehouse_name")
                                + "（" + row.get("before_qty") + " → " + row.get("after_qty") + "）",
                        Objects.toString(row.get("before_qty"), null), Objects.toString(row.get("after_qty"), null),
                        null, null, "INVENTORY", row.get("id"), row.get("transaction_no"), row.get("created_at"), null)));

        addLegacyFallbacks(events, orderId);
        events.sort(Comparator.comparing(item -> Objects.toString(item.get("event_at"), "")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", orderId);
        result.put("order_no", orderNo);
        result.put("supplier_name", order.get("supplier_name"));
        result.put("current_status", order.get("status"));
        result.put("expected_arrival_date", order.get("expected_arrival_date"));
        result.put("stages", stages(Objects.toString(order.get("status"), "")));
        result.put("events", events);
        result.put("generated_at", java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));
        result.put("read_only", true);
        return result;
    }

    private void addAudit(List<Map<String, Object>> events, String sql, long orderId) {
        jdbc.queryForList(sql, orderId).forEach(row -> {
            String action = Objects.toString(row.get("action_code"), "");
            events.add(event("AUDIT:" + row.get("id"), "AUDIT_LOG", stageForAction(action), titleForAction(action),
                    detailForAudit(row), Objects.toString(row.get("before_state"), null), Objects.toString(row.get("after_state"), null),
                    Objects.toString(row.get("operator_name"), null), Objects.toString(row.get("role_code"), null),
                    Objects.toString(row.get("target_type"), null), row.get("target_id"), row.get("reference_no"), row.get("created_at"), row.get("request_id")));
        });
    }

    private void addLegacyFallbacks(List<Map<String, Object>> events, long orderId) {
        fallback(events, "DELIVERY_NOTICE", jdbc.queryForList("select id,notice_no,status,created_at from delivery_notice where order_id=?", orderId),
                "DELIVERY", "交付通知已创建", "notice_no");
        fallback(events, "RECEIPT", jdbc.queryForList("select id,receipt_no,status,created_at from receipt where order_id=?", orderId),
                "RECEIPT", "收货记录已创建", "receipt_no");
        fallback(events, "RECONCILIATION", jdbc.queryForList("select id,reconciliation_no,status,created_at from reconciliation where order_id=?", orderId),
                "RECONCILIATION", "对账单已创建", "reconciliation_no");
    }

    private void fallback(List<Map<String, Object>> events, String targetType, List<Map<String, Object>> records,
            String stage, String title, String referenceColumn) {
        for (Map<String, Object> row : records) {
            String targetId = Objects.toString(row.get("id"), "");
            boolean audited = events.stream().anyMatch(item -> "AUDIT_LOG".equals(item.get("source"))
                    && targetType.equals(item.get("target_type")) && targetId.equals(Objects.toString(item.get("target_id"), "")));
            if (!audited) events.add(event("LEGACY:" + targetType + ":" + targetId, "BUSINESS_RECORD", stage, title,
                    "该记录来自业务表；早期或导入数据可能没有完整操作日志", null, Objects.toString(row.get("status"), null),
                    null, null, targetType, row.get("id"), row.get(referenceColumn), row.get("created_at"), null));
        }
    }

    private static Map<String, Object> event(String key, String source, String stage, String title, String description,
            String before, String after, String actor, String role, String targetType, Object targetId, Object referenceNo, Object eventAt, Object requestId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("event_key", key);
        item.put("source", source);
        item.put("stage", stage);
        item.put("title", title);
        item.put("description", description);
        item.put("before_state", before);
        item.put("after_state", after);
        item.put("actor_name", actor);
        item.put("actor_role", role);
        item.put("target_type", targetType);
        item.put("target_id", targetId);
        item.put("reference_no", referenceNo);
        item.put("event_at", eventAt);
        item.put("request_id", requestId);
        return item;
    }

    private static String detailForAudit(Map<String, Object> row) {
        String detail = Objects.toString(row.get("detail_text"), "").trim();
        if (!detail.isEmpty()) return detail;
        String before = Objects.toString(row.get("before_state"), "");
        String after = Objects.toString(row.get("after_state"), "");
        return !before.isEmpty() || !after.isEmpty() ? before + " → " + after : "操作已记录";
    }

    private static String stageForAction(String raw) {
        String action = raw.toUpperCase(Locale.ROOT);
        if (action.contains("RECONCILIATION") || action.contains("RECONCILING")) return "RECONCILIATION";
        if (action.contains("RECEIPT") || action.contains("RECEIVED")) return "RECEIPT";
        if (action.contains("INVENTORY")) return "INVENTORY";
        if (action.contains("NOTICE") || action.contains("SHIP") || action.contains("ARRIVED")) return "DELIVERY";
        if (action.contains("CONFIRM") || action.contains("REJECT")) return "CONFIRMATION";
        if (action.contains("COMPLETED")) return "COMPLETED";
        return "ORDER";
    }

    private static String titleForAction(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "GENERATE_ORDER" -> "订单已生成";
            case "ORDER_CONFIRM" -> "供应商已确认订单";
            case "ORDER_REJECT" -> "供应商已拒绝订单";
            case "ORDER_PREPARE_SHIPMENT" -> "供应商开始备货";
            case "CREATE_DELIVERY_NOTICE" -> "交付通知已创建";
            case "NOTICE_SUBMIT" -> "交付通知已提交";
            case "NOTICE_DISPATCH" -> "货物已发运";
            case "NOTICE_ARRIVE" -> "货物已到达";
            case "NOTICE_SHIP" -> "货物已发运";
            case "NOTICE_MARK_ARRIVED" -> "货物已到达";
            case "POST_RECEIPT" -> "收货验收已过账";
            case "ORDER_PARTIALLY_RECEIVED" -> "订单部分收货";
            case "ORDER_RECEIVED" -> "订单已完成收货";
            case "CREATE_RECONCILIATION" -> "对账单已创建";
            case "ORDER_RECONCILING" -> "订单进入对账";
            case "RECONCILIATION_CONFIRM" -> "供应商已确认对账";
            case "RECONCILIATION_DISPUTE" -> "供应商提出对账争议";
            case "RECONCILIATION_RESOLVE" -> "对账争议已处理";
            case "RECONCILIATION_COMPLETE", "ORDER_COMPLETED" -> "订单与对账已完成";
            default -> raw.replace('_', ' ');
        };
    }

    private static List<Map<String, Object>> stages(String status) {
        List<String[]> definitions = List.of(
                new String[]{"ORDER", "订单生成"}, new String[]{"CONFIRMATION", "供应商确认"},
                new String[]{"DELIVERY", "交付运输"}, new String[]{"RECEIPT", "收货入库"},
                new String[]{"RECONCILIATION", "双方对账"}, new String[]{"COMPLETED", "闭环完成"});
        int current = switch (status) {
            case "PENDING_CONFIRMATION" -> 1;
            case "CONFIRMED", "PENDING_SHIPMENT", "SHIPPED" -> 2;
            case "ARRIVED", "PARTIALLY_RECEIVED" -> 3;
            case "RECEIVED", "RECONCILING" -> 4;
            case "COMPLETED" -> 6;
            default -> 1;
        };
        boolean exception = List.of("REJECTED", "CANCELLED").contains(status);
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < definitions.size(); index++) {
            String stageStatus = current == 6 || index < current ? "DONE" : index == current ? "CURRENT" : "PENDING";
            if (exception && index == current) stageStatus = "EXCEPTION";
            result.add(Map.of("key", definitions.get(index)[0], "label", definitions.get(index)[1], "status", stageStatus));
        }
        return result;
    }
}
