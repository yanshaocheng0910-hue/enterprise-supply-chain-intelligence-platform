package com.scic.platform.system;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WarningService {
    private final JdbcTemplate jdbc;

    public WarningService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${scic.warning.refresh-delay-ms:60000}")
    @Transactional
    public void scheduledRefresh() {
        refreshRuleWarnings();
    }

    @Transactional
    public void refreshRuleWarnings() {
        refreshStockShortages();
        refreshOverdueOrders();
        refreshSupplierDeliveryRisk();
    }

    @Transactional
    public void recordEventWarning(String type, String severity, String targetType, long targetId,
                                   String title, String reason, String suggestion) {
        upsert(key(type, targetType, targetId), type, severity, targetType, targetId, title, reason, suggestion);
    }

    @Transactional
    public void resolveEventWarning(String type, String targetType, long targetId, String result) {
        resolve(key(type, targetType, targetId), result);
    }

    private void refreshStockShortages() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id,m.material_code,m.material_name,m.safety_stock,
                       coalesce(sum(i.on_hand_qty-i.reserved_qty+i.in_transit_qty),0) available_supply
                from material m left join inventory i on i.material_id=m.id
                where m.status='ACTIVE'
                group by m.id,m.material_code,m.material_name,m.safety_stock
                """);
        Set<String> active = new HashSet<>();
        for (Map<String, Object> row : rows) {
            BigDecimal available = decimal(row.get("available_supply"));
            BigDecimal safety = decimal(row.get("safety_stock"));
            long id = ((Number) row.get("id")).longValue();
            String sourceKey = key("STOCK_SHORTAGE", "MATERIAL", id);
            if (available.compareTo(safety) < 0) {
                active.add(sourceKey);
                String severity = available.signum() < 0 || available.compareTo(safety.multiply(new BigDecimal("0.5"))) < 0 ? "HIGH" : "MEDIUM";
                upsert(sourceKey, "STOCK_SHORTAGE", severity, "MATERIAL", id,
                        row.get("material_name") + "低于安全库存",
                        "可用供给" + available.stripTrailingZeros().toPlainString() + "，安全库存" + safety.stripTrailingZeros().toPlainString(),
                        "复核需求预测、在途数量并创建或调整采购需求");
            }
        }
        resolveMissing("STOCK_SHORTAGE", active, "库存规则复核后已恢复到安全库存");
    }

    private void refreshOverdueOrders() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select o.id,o.order_no,o.expected_arrival_date,o.status,
                       coalesce(sum(oi.quantity-oi.received_qty),0) remaining_qty
                from purchase_order o
                join purchase_order_item oi on oi.order_id=o.id
                where o.expected_arrival_date < current_date
                  and o.status not in ('ARRIVED','RECEIVED','RECONCILING','COMPLETED','CANCELLED','REJECTED')
                group by o.id,o.order_no,o.expected_arrival_date,o.status
                having coalesce(sum(oi.quantity-oi.received_qty),0) > 0
                """);
        Set<String> active = new HashSet<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String sourceKey = key("ORDER_DELAY", "PURCHASE_ORDER", id);
            active.add(sourceKey);
            LocalDate expected = row.get("expected_arrival_date") instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(row.get("expected_arrival_date").toString());
            long delayDays = java.time.temporal.ChronoUnit.DAYS.between(expected, LocalDate.now());
            BigDecimal remaining = decimal(row.get("remaining_qty"));
            upsert(sourceKey, "ORDER_DELAY", delayDays >= 3 ? "HIGH" : "MEDIUM", "PURCHASE_ORDER", id,
                    "订单" + row.get("order_no") + "已超过预计到货日",
                    "预计到货日为" + expected + "，当前逾期" + delayDays + "天，尚有" + remaining.stripTrailingZeros().toPlainString() + "未履约，订单状态为" + row.get("status"),
                    "联系供应商确认实际交期，并通过到货通知记录发运或异常情况");
        }
        resolveMissing("ORDER_DELAY", active, "订单已到货、结束或预计日期不再逾期");
    }

    private void refreshSupplierDeliveryRisk() {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,supplier_name,on_time_rate from supplier where status='ACTIVE'");
        Set<String> active = new HashSet<>();
        for (Map<String, Object> row : rows) {
            BigDecimal rate = decimal(row.get("on_time_rate"));
            long id = ((Number) row.get("id")).longValue();
            String sourceKey = key("SUPPLIER_DELIVERY", "SUPPLIER", id);
            if (rate.compareTo(new BigDecimal("0.9000")) < 0) {
                active.add(sourceKey);
                upsert(sourceKey, "SUPPLIER_DELIVERY", rate.compareTo(new BigDecimal("0.8000")) < 0 ? "HIGH" : "MEDIUM", "SUPPLIER", id,
                        row.get("supplier_name") + "准时交付率低于目标",
                        "当前准时交付率为" + rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString() + "%",
                        "复核近期订单交付记录，并在下一采购计划中评估交期风险");
            }
        }
        resolveMissing("SUPPLIER_DELIVERY", active, "供应商准时交付率已恢复到目标值");
    }

    private void upsert(String sourceKey, String type, String severity, String targetType, long targetId,
                        String title, String reason, String suggestion) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,status,condition_active from warning_record where source_key=?", sourceKey);
        if (rows.isEmpty()) {
            jdbc.update("insert into warning_record(warning_no,warning_type,severity,target_type,target_id,title,reason_text,suggestion_text,status,source_key,condition_active,last_detected_at,updated_at) values(?,?,?,?,?,?,?,?, 'OPEN',?,true,current_timestamp,current_timestamp)",
                    number(), type, severity, targetType, targetId, title, reason, suggestion, sourceKey);
            return;
        }
        Map<String, Object> existing = rows.get(0);
        boolean wasActive = Boolean.TRUE.equals(existing.get("condition_active"));
        String status = existing.get("status").toString();
        boolean reopen = !wasActive && "CLOSED".equals(status);
        if (reopen) {
            jdbc.update("update warning_record set warning_type=?,severity=?,target_type=?,target_id=?,title=?,reason_text=?,suggestion_text=?,status='OPEN',condition_active=true,handled_by=null,handled_result=null,handled_at=null,last_detected_at=current_timestamp,updated_at=current_timestamp where source_key=?",
                    type, severity, targetType, targetId, title, reason, suggestion, sourceKey);
        } else {
            jdbc.update("update warning_record set warning_type=?,severity=?,target_type=?,target_id=?,title=?,reason_text=?,suggestion_text=?,condition_active=true,last_detected_at=current_timestamp,updated_at=current_timestamp where source_key=?",
                    type, severity, targetType, targetId, title, reason, suggestion, sourceKey);
        }
    }

    private void resolveMissing(String type, Set<String> activeKeys, String result) {
        for (Map<String, Object> row : jdbc.queryForList("select source_key from warning_record where warning_type=? and source_key is not null and condition_active=true", type)) {
            String sourceKey = row.get("source_key").toString();
            if (!activeKeys.contains(sourceKey)) resolve(sourceKey, result);
        }
    }

    private void resolve(String sourceKey, String result) {
        jdbc.update("update warning_record set status='CLOSED',condition_active=false,handled_result=?,handled_at=current_timestamp,updated_at=current_timestamp where source_key=? and condition_active=true", result, sourceKey);
    }

    private static String key(String type, String targetType, long targetId) {
        return type + ":" + targetType + ":" + targetId;
    }

    private static String number() {
        return "WARN-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
}
