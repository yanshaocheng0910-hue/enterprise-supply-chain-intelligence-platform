package com.scic.platform.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scic.platform.common.BusinessException;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import com.scic.platform.system.AuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisReportService {
    private static final Set<String> SECTION_KEYS = Set.of(
            "executive_summary", "demand_inventory", "supplier_fulfillment",
            "reconciliation_finance", "warning_risk", "recommendations", "boundary");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AiGateway ai;
    private final AuditService audit;

    public AnalysisReportService(JdbcTemplate jdbc, ObjectMapper json, AiGateway ai, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ai = ai;
        this.audit = audit;
    }

    public List<Map<String, Object>> reports() {
        return jdbc.queryForList("select r.id,r.report_no,r.as_of_time,r.provider,r.model_name,r.prompt_version,r.fallback_reason,r.data_fingerprint,r.created_at,u.display_name created_by_name from supply_chain_analysis_report r join sys_user u on u.id=r.created_by order by r.created_at desc,r.id desc limit 100");
    }

    public Map<String, Object> report(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select r.*,u.display_name created_by_name from supply_chain_analysis_report r join sys_user u on u.id=r.created_by where r.id=?", id);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "经营分析报告不存在", HttpStatus.NOT_FOUND);
        return view(rows.get(0));
    }

    public Map<String, Object> generate(String requestId, String idempotencyKey) {
        List<Map<String, Object>> existing = jdbc.queryForList("select id from supply_chain_analysis_report where idempotency_key=?", idempotencyKey);
        if (!existing.isEmpty()) return report(((Number) existing.get(0).get("id")).longValue());

        AuthUser user = SecuritySupport.currentUser();
        OffsetDateTime asOf = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> context = buildContext(asOf);
        String contextJson = write(context);
        String fingerprint = sha256(contextJson);
        Map<String, Object> payload = new LinkedHashMap<>(context);
        payload.put("data_fingerprint", fingerprint);
        Map<String, Object> generated = ai.analysisReport(payload, requestId);
        validate(generated);

        String reportNo = "AIR-" + asOf.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String provider = text(generated.get("provider"), "unknown");
        String modelName = text(generated.get("model_name"), "unknown");
        String promptVersion = text(generated.get("prompt_version"), "unknown");
        String fallbackReason = nullableText(generated.get("fallback_reason"));
        try {
            jdbc.update("insert into supply_chain_analysis_report(report_no,as_of_time,provider,model_name,prompt_version,fallback_reason,data_fingerprint,context_json,report_json,idempotency_key,created_by) values(?,?,?,?,?,?,?,?,?,?,?)",
                    reportNo, java.sql.Timestamp.from(asOf.toInstant()), provider, modelName, promptVersion,
                    fallbackReason, fingerprint, contextJson, write(generated), idempotencyKey, user.id());
        } catch (DuplicateKeyException ex) {
            List<Map<String, Object>> raced = jdbc.queryForList("select id from supply_chain_analysis_report where idempotency_key=?", idempotencyKey);
            if (!raced.isEmpty()) return report(((Number) raced.get(0).get("id")).longValue());
            throw ex;
        }
        Long id = jdbc.queryForObject("select id from supply_chain_analysis_report where report_no=?", Long.class, reportNo);
        audit.log(requestId, "GENERATE_ANALYSIS_REPORT", "ANALYSIS_REPORT", id, null, "CREATED",
                "provider=" + provider + ";model=" + modelName + ";fingerprint=" + fingerprint);
        return report(Objects.requireNonNull(id));
    }

    private Map<String, Object> buildContext(OffsetDateTime asOf) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("active_orders", number("select count(*) from purchase_order where status not in ('COMPLETED','CANCELLED','REJECTED')"));
        metrics.put("overdue_orders", number("select count(*) from purchase_order where expected_arrival_date<current_date and status not in ('COMPLETED','CANCELLED','REJECTED')"));
        metrics.put("inventory_shortages", number("select count(*) from inventory i join material m on m.id=i.material_id where i.on_hand_qty-i.reserved_qty+i.in_transit_qty<m.safety_stock"));
        metrics.put("open_warnings", number("select count(*) from warning_record where status in ('OPEN','ACKNOWLEDGED')"));
        metrics.put("high_warnings", number("select count(*) from warning_record where status='OPEN' and severity in ('HIGH','CRITICAL')"));
        metrics.put("pending_plans", number("select count(*) from purchase_plan where status='PENDING_APPROVAL'"));
        metrics.put("pending_reconciliations", number("select count(*) from reconciliation where status not in ('COMPLETED','CLOSED','CONFIRMED','RESOLVED')"));
        metrics.put("reconciliation_difference_amount", decimal("select coalesce(sum(abs(difference_amount)),0) from reconciliation where status not in ('COMPLETED','CLOSED','CONFIRMED','RESOLVED')"));
        metrics.put("rejected_quantity", decimal("select coalesce(sum(rejected_qty),0) from receipt_item"));
        metrics.put("active_suppliers", number("select count(*) from supplier where status='ACTIVE'"));
        metrics.put("average_on_time_rate", decimal("select coalesce(avg(on_time_rate),0) from supplier where status='ACTIVE'"));

        List<Map<String, Object>> risks = jdbc.queryForList("select warning_type kind,title label,severity,created_at from warning_record where status in ('OPEN','ACKNOWLEDGED') order by case severity when 'CRITICAL' then 1 when 'HIGH' then 2 when 'MEDIUM' then 3 else 4 end,created_at desc limit 8");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("as_of_time", asOf.toString());
        context.put("timezone", "Asia/Shanghai");
        context.put("scope", "企业全局供应链业务库，只读统计快照");
        context.put("metrics", metrics);
        context.put("top_risks", risks);
        return context;
    }

    private void validate(Map<String, Object> generated) {
        Object rawSections = generated.get("sections");
        if (!(rawSections instanceof List<?>)) invalid();
        List<?> sections = (List<?>) rawSections;
        if (sections.size() != SECTION_KEYS.size()) invalid();
        Set<String> actual = sections.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(section -> Objects.toString(section.get("key"), "")).collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(SECTION_KEYS)) invalid();
        if (!Set.of("rule", "openai-compatible").contains(Objects.toString(generated.get("provider"), ""))) invalid();
        if (!(generated.get("priority_actions") instanceof List<?>)) invalid();
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = readMap(row.get("report_json"));
        out.put("id", row.get("id"));
        out.put("report_no", row.get("report_no"));
        out.put("as_of_time", row.get("as_of_time"));
        out.put("data_fingerprint", row.get("data_fingerprint"));
        out.put("created_by_name", row.get("created_by_name"));
        out.put("created_at", row.get("created_at"));
        out.put("context", readMap(row.get("context_json")));
        return out;
    }

    private Number number(String sql) { return Objects.requireNonNullElse(jdbc.queryForObject(sql, Long.class), 0L); }
    private BigDecimal decimal(String sql) { return Objects.requireNonNullElse(jdbc.queryForObject(sql, BigDecimal.class), BigDecimal.ZERO); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("JSON serialization failed", ex); } }
    private Map<String, Object> readMap(Object value) { try { return json.readValue(Objects.toString(value, "{}"), new TypeReference<>() {}); } catch (Exception ex) { throw new IllegalStateException("JSON deserialization failed", ex); } }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static String text(Object value, String fallback) { String result = Objects.toString(value, "").trim(); return result.isEmpty() ? fallback : result; }
    private static String nullableText(Object value) { String result = Objects.toString(value, "").trim(); return result.isEmpty() ? null : result; }
    private static void invalid() { throw new BusinessException("AI_OUTPUT_INVALID", "AI 服务返回的经营分析报告未通过结构校验", HttpStatus.BAD_GATEWAY); }
}
