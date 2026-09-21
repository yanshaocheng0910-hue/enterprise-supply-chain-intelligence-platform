package com.scic.platform.dashboard;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/work-queue")
public class WorkQueueController {
    private final WorkQueueService service;

    public WorkQueueController(WorkQueueService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<Map<String, Object>> queue(HttpServletRequest request) {
        return ApiResponse.ok(service.queue(), RequestIds.get(request));
    }
}
