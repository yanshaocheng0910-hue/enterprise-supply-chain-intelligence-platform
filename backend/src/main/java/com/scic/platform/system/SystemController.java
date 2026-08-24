package com.scic.platform.system;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.BusinessException;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SystemController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/warnings")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','MANAGER')")
    public ApiResponse<List<Map<String, Object>>> warnings(@RequestParam(required = false) String status, HttpServletRequest request) {
        String sql = "select id,warning_no,warning_type,severity,target_type,target_id,title,reason_text,suggestion_text,status,handled_result,handled_at,created_at from warning_record";
        List<Map<String, Object>> rows = status == null || status.isBlank()
                ? jdbc.queryForList(sql + " order by case severity when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,created_at desc")
                : jdbc.queryForList(sql + " where status=? order by created_at desc", status);
        return ApiResponse.ok(rows, RequestIds.get(request));
    }

    @PostMapping("/warnings/{id}/handle")
    @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<Map<String, Object>> handleWarning(@PathVariable long id, @Valid @RequestBody WarningAction input, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList("select status from warning_record where id=?", id);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "预警不存在", HttpStatus.NOT_FOUND);
        String before = rows.get(0).get("status").toString();
        if ("CLOSED".equals(before)) throw new BusinessException("ILLEGAL_STATE", "已关闭预警不能重复处理", HttpStatus.CONFLICT);
        String after = input.close() ? "CLOSED" : "ACKNOWLEDGED";
        long userId = com.scic.platform.security.SecuritySupport.currentUser().id();
        jdbc.update("update warning_record set status=?,handled_by=?,handled_result=?,handled_at=current_timestamp where id=?", after, userId, input.result(), id);
        audit.log(RequestIds.get(request), "HANDLE_WARNING", "WARNING", id, before, after, input.result());
        return ApiResponse.ok(Map.of("id", id, "status", after), RequestIds.get(request));
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    public ApiResponse<Map<String, Object>> auditLogs(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "30") int size,
                                                      HttpServletRequest request) {
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.max(0, page) * safeSize;
        List<Map<String, Object>> items = jdbc.queryForList("select id,request_id,operator_name,role_code,action_code,target_type,target_id,before_state,after_state,detail_text,created_at from operation_log order by id desc limit ? offset ?", safeSize, offset);
        Number total = jdbc.queryForObject("select count(*) from operation_log", Number.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", Math.max(0, page));
        result.put("size", safeSize);
        result.put("total", total == null ? 0 : total.longValue());
        return ApiResponse.ok(result, RequestIds.get(request));
    }

    @GetMapping("/data-provenance")
    @PreAuthorize("hasAnyRole('ADMIN','BUYER','MANAGER')")
    public ApiResponse<Map<String, Object>> provenance(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("demandSources", jdbc.queryForList("select source_system,data_label,count(*) records,min(demand_date) first_date,max(demand_date) last_date from demand_history group by source_system,data_label"));
        data.put("importBatches", jdbc.queryForList("select id,batch_no,import_type,source_system,original_filename,status,total_rows,success_rows,failed_rows,created_at from data_import_batch order by id desc limit 20"));
        data.put("statement", "DEMO_SYNTHETIC 仅用于演示与测试；CSV 导入记录保留来源系统、文件哈希、批次和错误明细。");
        return ApiResponse.ok(data, RequestIds.get(request));
    }

    public record WarningAction(@NotBlank String result, boolean close) {}
}
