package com.scic.platform.collaboration;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/collaboration")
public class CollaborationController {
    private final CollaborationService service;

    public CollaborationController(CollaborationService service) { this.service = service; }

    @GetMapping("/delivery-notices")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<List<Map<String,Object>>> notices(HttpServletRequest req) { return ApiResponse.ok(service.notices(), RequestIds.get(req)); }

    @GetMapping("/delivery-notices/{id}")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<Map<String,Object>> notice(@PathVariable long id,HttpServletRequest req){ return ApiResponse.ok(service.notice(id),RequestIds.get(req)); }

    @PostMapping("/delivery-notices")
    @PreAuthorize("hasRole('SUPPLIER')")
    public ApiResponse<Map<String,Object>> createNotice(@Valid @RequestBody NoticeRequest input,HttpServletRequest req){
        var items=input.items()==null?List.<CollaborationService.NoticeItemInput>of():input.items().stream().map(i->new CollaborationService.NoticeItemInput(i.orderItemId(),i.quantity())).toList();
        var command=new CollaborationService.NoticeInput(input.orderId(),input.expectedArrivalAt(),input.carrierName(),input.trackingNo(),items);
        return ApiResponse.ok(service.createNotice(RequestIds.get(req),command,"FORM",null),RequestIds.get(req));
    }

    @PostMapping("/delivery-notices/{id}/actions")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> noticeAction(@PathVariable long id,@Valid @RequestBody ActionRequest input,HttpServletRequest req){ return ApiResponse.ok(service.transitionNotice(RequestIds.get(req),id,input.expectedVersion(),input.action(),input.note()),RequestIds.get(req)); }

    @GetMapping("/receipts")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<List<Map<String,Object>>> receipts(HttpServletRequest req){ return ApiResponse.ok(service.receipts(),RequestIds.get(req)); }

    @GetMapping("/receipts/{id}")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<Map<String,Object>> receipt(@PathVariable long id,HttpServletRequest req){ return ApiResponse.ok(service.receipt(id),RequestIds.get(req)); }

    @PostMapping("/receipts")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String,Object>> receive(@RequestHeader(name="Idempotency-Key") String key,@Valid @RequestBody ReceiptRequest input,HttpServletRequest req){
        var items=input.items().stream().map(i->new CollaborationService.ReceiptItemInput(i.orderItemId(),i.receivedQty(),i.qualifiedQty(),i.rejectedQty(),i.varianceReason())).toList();
        var command=new CollaborationService.ReceiptInput(input.orderId(),input.deliveryNoticeId(),input.orderVersion(),input.warehouseCode(),input.receivedAt(),input.notes(),items);
        return ApiResponse.ok(service.receive(RequestIds.get(req),command,key),RequestIds.get(req));
    }

    @GetMapping("/reconciliations")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<List<Map<String,Object>>> reconciliations(HttpServletRequest req){ return ApiResponse.ok(service.reconciliations(),RequestIds.get(req)); }

    @GetMapping("/reconciliations/{id}")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER','MANAGER')")
    public ApiResponse<Map<String,Object>> reconciliation(@PathVariable long id,HttpServletRequest req){ return ApiResponse.ok(service.reconciliation(id),RequestIds.get(req)); }

    @PostMapping("/reconciliations")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String,Object>> createReconciliation(@RequestHeader(name="Idempotency-Key") String key,@Valid @RequestBody ReconciliationCreate input,HttpServletRequest req){ return ApiResponse.ok(service.createReconciliation(RequestIds.get(req),input.orderId(),input.orderVersion(),key),RequestIds.get(req)); }

    @PostMapping("/reconciliations/{id}/actions")
    @PreAuthorize("hasAnyRole('BUYER','SUPPLIER')")
    public ApiResponse<Map<String,Object>> reconciliationAction(@PathVariable long id,@Valid @RequestBody ActionRequest input,HttpServletRequest req){ return ApiResponse.ok(service.transitionReconciliation(RequestIds.get(req),id,input.expectedVersion(),input.action(),input.note()),RequestIds.get(req)); }

    public record NoticeItemRequest(@NotNull Long orderItemId,@NotNull @DecimalMin("0.0001") BigDecimal quantity){}
    public record NoticeRequest(@NotNull Long orderId,@NotNull OffsetDateTime expectedArrivalAt,String carrierName,String trackingNo,List<@Valid NoticeItemRequest> items){}
    public record ReceiptItemRequest(@NotNull Long orderItemId,@NotNull @DecimalMin("0.0001") BigDecimal receivedQty,@NotNull @DecimalMin("0") BigDecimal qualifiedQty,@NotNull @DecimalMin("0") BigDecimal rejectedQty,String varianceReason){}
    public record ReceiptRequest(@NotNull Long orderId,Long deliveryNoticeId,@NotNull Integer orderVersion,@NotBlank String warehouseCode,OffsetDateTime receivedAt,String notes,@NotEmpty List<@Valid ReceiptItemRequest> items){}
    public record ReconciliationCreate(@NotNull Long orderId,@NotNull Integer orderVersion){}
    public record ActionRequest(@NotBlank String action,@NotNull Integer expectedVersion,String note){}
}
