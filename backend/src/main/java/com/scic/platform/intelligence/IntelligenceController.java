package com.scic.platform.intelligence;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/intelligence")
public class IntelligenceController {
    private final IntelligenceService service;
    public IntelligenceController(IntelligenceService service){this.service=service;}

    @GetMapping("/forecast-runs") @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<List<Map<String,Object>>> runs(@RequestParam(required=false) String materialCode,HttpServletRequest r){return ApiResponse.ok(service.forecastRuns(materialCode),RequestIds.get(r));}
    @GetMapping("/forecast-runs/{id}") @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<Map<String,Object>> run(@PathVariable long id,HttpServletRequest r){return ApiResponse.ok(service.forecastRun(id),RequestIds.get(r));}
    @PostMapping("/forecast-runs") @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String,Object>> forecast(@Valid @RequestBody ForecastRequest b,HttpServletRequest r){return ApiResponse.ok(service.runForecast(RequestIds.get(r),b.materialCode()),RequestIds.get(r));}

    @GetMapping("/parse-records") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER','ADMIN')")
    public ApiResponse<List<Map<String,Object>>> records(HttpServletRequest r){return ApiResponse.ok(service.parseRecords(),RequestIds.get(r));}
    @GetMapping("/parse-previews/{id}") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER','ADMIN')")
    public ApiResponse<Map<String,Object>> preview(@PathVariable long id,HttpServletRequest r){return ApiResponse.ok(service.previewRecord(id),RequestIds.get(r));}
    @PostMapping("/parse-previews") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> parse(@Valid @RequestBody ParseRequest b,HttpServletRequest r){return ApiResponse.ok(service.createPreview(RequestIds.get(r),b.taskType(),b.text(),b.context()),RequestIds.get(r));}
    @PatchMapping("/parse-previews/{id}") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> correct(@PathVariable long id,@Valid @RequestBody CorrectionRequest b,HttpServletRequest r){return ApiResponse.ok(service.updatePreview(RequestIds.get(r),id,b.expectedVersion(),b.normalizedFields()),RequestIds.get(r));}
    @PostMapping("/parse-previews/{id}/confirm") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> confirm(@PathVariable long id,@RequestHeader(name="Idempotency-Key") String key,@Valid @RequestBody VersionRequest b,HttpServletRequest r){return ApiResponse.ok(service.confirmPreview(RequestIds.get(r),id,b.expectedVersion(),key),RequestIds.get(r));}
    @PostMapping("/parse-previews/{id}/cancel") @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> cancel(@PathVariable long id,@Valid @RequestBody VersionRequest b,HttpServletRequest r){return ApiResponse.ok(service.cancelPreview(RequestIds.get(r),id,b.expectedVersion()),RequestIds.get(r));}

    public record ForecastRequest(@NotBlank String materialCode){}
    public record ParseRequest(@NotBlank String taskType,@NotBlank @Size(max=4000) String text,Map<String,Object> context){}
    public record CorrectionRequest(@NotNull Integer expectedVersion,@NotNull Map<String,Object> normalizedFields){}
    public record VersionRequest(@NotNull Integer expectedVersion){}
}
