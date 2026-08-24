package com.scic.platform.importer;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import com.scic.platform.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/imports")
@PreAuthorize("hasRole('BUYER')")
public class DataImportController {
    private final DataImportService service;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public DataImportController(DataImportService service, AuditService audit, JdbcTemplate jdbc) {
        this.service = service;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> batches(HttpServletRequest request) {
        return ApiResponse.ok(jdbc.queryForList(
                "select id,batch_no,import_type,source_system,original_filename,status,total_rows,success_rows,failed_rows,created_at,finished_at " +
                        "from data_import_batch order by id desc limit 100"), RequestIds.get(request));
    }

    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> upload(@RequestParam String type,
                                                   @RequestParam(defaultValue = "CSV_ADAPTER") String sourceSystem,
                                                   @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                   @RequestParam MultipartFile file,
                                                   HttpServletRequest request) {
        Map<String, Object> result = service.importCsv(type, sourceSystem, idempotencyKey, file);
        Number id = (Number) result.get("id");
        if (!Boolean.TRUE.equals(result.get("replayed"))) audit.log(RequestIds.get(request), "IMPORT_CSV", "IMPORT_BATCH", id == null ? null : id.longValue(), null, result.get("status").toString(), type);
        return ApiResponse.ok(result, RequestIds.get(request));
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ApiResponse<Map<String, Object>> preview(@RequestParam String type,
                                                    @RequestParam(defaultValue = "CSV_ADAPTER") String sourceSystem,
                                                    @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @RequestParam MultipartFile file,
                                                    HttpServletRequest request) {
        Map<String,Object> result=service.previewCsv(type,sourceSystem,idempotencyKey,file);
        Number id=(Number)result.get("id");
        if(!Boolean.TRUE.equals(result.get("replayed"))) audit.log(RequestIds.get(request),"PREVIEW_CSV","IMPORT_BATCH",id==null?null:id.longValue(),null,result.get("status").toString(),type);
        return ApiResponse.ok(result,RequestIds.get(request));
    }

    @PostMapping("/{batchId}/commit")
    public ApiResponse<Map<String,Object>> commit(@PathVariable long batchId,HttpServletRequest request){
        Map<String,Object> result=service.commit(batchId);
        audit.log(RequestIds.get(request),"COMMIT_CSV","IMPORT_BATCH",batchId,"VALIDATED",result.get("status").toString(),null);
        return ApiResponse.ok(result,RequestIds.get(request));
    }

    @GetMapping("/{batchId}/errors")
    public ApiResponse<List<Map<String, Object>>> errors(@PathVariable long batchId, HttpServletRequest request) {
        return ApiResponse.ok(service.errors(batchId), RequestIds.get(request));
    }
}
