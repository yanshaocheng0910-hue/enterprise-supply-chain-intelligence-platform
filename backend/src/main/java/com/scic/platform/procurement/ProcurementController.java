package com.scic.platform.procurement;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/procurement")
public class ProcurementController {
    private final ProcurementService service;

    public ProcurementController(ProcurementService service) {
        this.service = service;
    }

    @GetMapping("/demands")
    @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<List<Map<String, Object>>> demands(HttpServletRequest request) {
        return ApiResponse.ok(service.demands(), RequestIds.get(request));
    }

    @PostMapping("/demands")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String, Object>> createDemand(@Valid @RequestBody DemandRequest input, HttpServletRequest request) {
        var command = new ProcurementService.DemandInput(input.materialCode(), input.quantity(), input.expectedDate(), input.priority(), input.notes());
        return ApiResponse.ok(service.createDemand(RequestIds.get(request), command, "MANUAL", null), RequestIds.get(request));
    }

    @GetMapping("/plans")
    @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<List<Map<String, Object>>> plans(HttpServletRequest request) {
        return ApiResponse.ok(service.plans(), RequestIds.get(request));
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<Map<String, Object>> plan(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.plan(id), RequestIds.get(request));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String, Object>> createPlan(@Valid @RequestBody PlanRequest input, HttpServletRequest request) {
        List<ProcurementService.PlanItemInput> items = input.items().stream().map(i -> new ProcurementService.PlanItemInput(i.demandId(), i.materialCode(), i.supplierCode(), i.quantity(), i.unitPrice(), i.expectedDate())).toList();
        return ApiResponse.ok(service.createPlan(RequestIds.get(request), new ProcurementService.PlanInput(input.planName(), items)), RequestIds.get(request));
    }

    @PostMapping("/plans/{id}/actions")
    @PreAuthorize("hasAnyRole('BUYER','MANAGER')")
    public ApiResponse<Map<String, Object>> planAction(@PathVariable long id, @Valid @RequestBody StateAction input, HttpServletRequest request) {
        return ApiResponse.ok(service.transitionPlan(RequestIds.get(request), id, input.expectedVersion(), input.action(), input.note()), RequestIds.get(request));
    }

    @PostMapping("/plans/{id}/generate-orders")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<List<Map<String, Object>>> generateOrders(@PathVariable long id,
                                                                 @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
                                                                 @Valid @RequestBody VersionRequest input,
                                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.generateOrders(RequestIds.get(request), id, input.expectedVersion(), idempotencyKey), RequestIds.get(request));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<List<Map<String, Object>>> orders(HttpServletRequest request) {
        return ApiResponse.ok(service.orders(), RequestIds.get(request));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<Map<String, Object>> order(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.order(id), RequestIds.get(request));
    }

    @PostMapping("/orders/{id}/actions")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String, Object>> orderAction(@PathVariable long id, @Valid @RequestBody StateAction input, HttpServletRequest request) {
        return ApiResponse.ok(service.transitionOrder(RequestIds.get(request), id, input.expectedVersion(), input.action(), input.note()), RequestIds.get(request));
    }

    public record DemandRequest(@NotBlank String materialCode,
                                @NotNull @DecimalMin("0.0001") BigDecimal quantity,
                                @NotNull @FutureOrPresent LocalDate expectedDate,
                                String priority, String notes) {}

    public record PlanItemRequest(Long demandId, @NotBlank String materialCode, String supplierCode,
                                  @NotNull @DecimalMin("0.0001") BigDecimal quantity,
                                  @DecimalMin("0") BigDecimal unitPrice,
                                  @NotNull @FutureOrPresent LocalDate expectedDate) {}

    public record PlanRequest(@NotBlank String planName, @NotEmpty List<@Valid PlanItemRequest> items) {}
    public record StateAction(@NotBlank String action, @NotNull Integer expectedVersion, String note) {}
    public record VersionRequest(@NotNull Integer expectedVersion) {}
}
