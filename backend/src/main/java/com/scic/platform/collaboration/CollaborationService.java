package com.scic.platform.collaboration;

import com.scic.platform.common.BusinessException;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import com.scic.platform.system.AuditService;
import com.scic.platform.system.WarningService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Service
public class CollaborationService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final WarningService warnings;

    public CollaborationService(JdbcTemplate jdbc, AuditService audit, WarningService warnings) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.warnings = warnings;
    }

    public List<Map<String, Object>> notices() {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select n.id,n.notice_no,n.order_id,o.order_no,s.supplier_code,s.supplier_name,n.status,n.expected_arrival_at,n.carrier_name,n.tracking_no,n.exception_note,n.source_type,n.version,n.created_at,n.updated_at,coalesce(sum(ni.quantity),0) quantity from delivery_notice n join purchase_order o on o.id=n.order_id join supplier s on s.id=n.supplier_id left join delivery_notice_item ni on ni.notice_id=n.id";
        String group = " group by n.id,n.notice_no,n.order_id,o.order_no,s.supplier_code,s.supplier_name,n.status,n.expected_arrival_at,n.carrier_name,n.tracking_no,n.exception_note,n.source_type,n.version,n.created_at,n.updated_at";
        if ("SUPPLIER".equals(user.role())) return jdbc.queryForList(sql + " where n.supplier_id=?" + group + " order by n.updated_at desc", user.supplierId());
        return jdbc.queryForList(sql + group + " order by n.updated_at desc");
    }

    @Transactional
    public Map<String, Object> createNotice(String requestId, NoticeInput input, String sourceType, String sourceRef) {
        AuthUser user = SecuritySupport.currentUser();
        if (!"SUPPLIER".equals(user.role())) throw forbidden("只有供应商可创建到货通知");
        Map<String, Object> order = orderRow(input.orderId());
        long supplierId = ((Number) order.get("supplier_id")).longValue();
        if (user.supplierId() == null || supplierId != user.supplierId()) throw notFound("采购订单不存在");
        String orderStatus = order.get("status").toString();
        if (!List.of("CONFIRMED", "PENDING_SHIPMENT", "PARTIALLY_RECEIVED").contains(orderStatus)) throw state(orderStatus);
        Number active = jdbc.queryForObject("select count(*) from delivery_notice where order_id=? and status not in ('CANCELLED','RECEIVED')", Number.class, input.orderId());
        if (active != null && active.longValue() > 0) throw new BusinessException("DUPLICATE_RESOURCE", "该订单已有有效到货通知", HttpStatus.CONFLICT);

        String noticeNo = number("DN");
        jdbc.update("insert into delivery_notice(notice_no,order_id,supplier_id,status,expected_arrival_at,carrier_name,tracking_no,source_type,created_by) values(?,?,?,?,?,?,?,?,?)",
                noticeNo, input.orderId(), supplierId, "DRAFT", java.sql.Timestamp.from(input.expectedArrivalAt().toInstant()), input.carrierName(), input.trackingNo(),
                sourceType == null ? "FORM" : sourceType, user.id());
        Long id = jdbc.queryForObject("select id from delivery_notice where notice_no=?", Long.class, noticeNo);
        List<Map<String, Object>> orderItems = jdbc.queryForList("select id,quantity,received_qty from purchase_order_item where order_id=? order by id", input.orderId());
        Map<Long, BigDecimal> requested = new LinkedHashMap<>();
        if (input.items() != null) for (NoticeItemInput i : input.items()) {
            if (requested.put(i.orderItemId(), i.quantity()) != null) throw rule("到货通知不能重复提交同一订单明细");
        }
        Set<Long> orderItemIds = new HashSet<>();
        for (Map<String, Object> item : orderItems) orderItemIds.add(((Number) item.get("id")).longValue());
        if (!orderItemIds.containsAll(requested.keySet())) throw rule("到货通知明细不属于当前订单");
        for (Map<String, Object> item : orderItems) {
            long itemId = ((Number) item.get("id")).longValue();
            BigDecimal remaining = decimal(item.get("quantity")).subtract(decimal(item.get("received_qty")));
            BigDecimal qty = requested.isEmpty() ? remaining : requested.get(itemId);
            if (qty == null) continue;
            if (qty.signum() <= 0 || qty.compareTo(remaining) > 0) throw rule("到货通知数量必须大于0且不超过订单剩余数量");
            jdbc.update("insert into delivery_notice_item(notice_id,order_item_id,quantity) values(?,?,?)", id, itemId, qty);
        }
        Number count = jdbc.queryForObject("select count(*) from delivery_notice_item where notice_id=?", Number.class, id);
        if (count == null || count.longValue() == 0) throw rule("到货通知必须至少包含一个订单明细");
        audit.log(requestId, "CREATE_DELIVERY_NOTICE", "DELIVERY_NOTICE", id, null, "DRAFT", sourceRef == null ? noticeNo : sourceRef);
        return notice(id);
    }

    public Map<String, Object> notice(long id) {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select n.id,n.notice_no,n.order_id,o.order_no,n.supplier_id,s.supplier_code,s.supplier_name,n.status,n.expected_arrival_at,n.carrier_name,n.tracking_no,n.exception_note,n.source_type,n.version,n.created_at,n.updated_at from delivery_notice n join purchase_order o on o.id=n.order_id join supplier s on s.id=n.supplier_id where n.id=?";
        List<Map<String, Object>> rows = "SUPPLIER".equals(user.role()) ? jdbc.queryForList(sql + " and n.supplier_id=?", id, user.supplierId()) : jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw notFound("到货通知不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("select ni.id,ni.order_item_id,m.material_code,m.material_name,m.unit,ni.quantity from delivery_notice_item ni join purchase_order_item oi on oi.id=ni.order_item_id join material m on m.id=oi.material_id where ni.notice_id=? order by ni.id", id));
        return result;
    }

    @Transactional
    public Map<String, Object> transitionNotice(String requestId, long id, int expectedVersion, String rawAction, String note) {
        AuthUser user = SecuritySupport.currentUser();
        Map<String, Object> row = one("select status,version,supplier_id,order_id from delivery_notice where id=?", id, "到货通知不存在");
        long supplierId = ((Number) row.get("supplier_id")).longValue();
        if ("SUPPLIER".equals(user.role()) && (user.supplierId() == null || supplierId != user.supplierId())) throw notFound("到货通知不存在");
        String before = row.get("status").toString();
        int version = ((Number) row.get("version")).intValue();
        if (version != expectedVersion) throw conflict(version);
        long orderId = ((Number) row.get("order_id")).longValue();
        String action = rawAction.toUpperCase(Locale.ROOT);
        String after;
        switch (action) {
            case "SUBMIT" -> { requireSupplier(user); require(before, "DRAFT"); after = "SUBMITTED"; }
            case "DISPATCH" -> { requireSupplier(user); require(before, "SUBMITTED"); after = "IN_TRANSIT"; syncOrder(requestId, orderId, List.of("PENDING_SHIPMENT", "PARTIALLY_RECEIVED"), "SHIPPED", "NOTICE_DISPATCH"); }
            case "ARRIVE" -> { requireBuyer(user); require(before, "IN_TRANSIT"); after = "ARRIVED"; syncOrder(requestId, orderId, List.of("SHIPPED", "PARTIALLY_RECEIVED"), "ARRIVED", "NOTICE_ARRIVE"); }
            case "REPORT_EXCEPTION" -> { requireSupplier(user); require(before, "IN_TRANSIT"); if (blank(note)) throw rule("上报异常必须填写说明"); after = "EXCEPTION"; }
            case "RESUME" -> { requireSupplier(user); require(before, "EXCEPTION"); if (blank(note)) throw rule("恢复运输必须填写处理说明"); after = "IN_TRANSIT"; }
            case "CANCEL" -> { requireSupplier(user); if (!List.of("DRAFT", "SUBMITTED").contains(before)) throw state(before); if (blank(note)) throw rule("取消通知必须填写原因"); after = "CANCELLED"; }
            default -> throw rule("未知到货通知动作: " + rawAction);
        }
        int updated = jdbc.update("update delivery_notice set status=?,exception_note=?,version=version+1,updated_at=current_timestamp where id=? and version=? and status=?",
                after, List.of("EXCEPTION", "CANCELLED").contains(after) ? note : ("IN_TRANSIT".equals(after) && "RESUME".equals(action) ? note : row.get("exception_note")), id, expectedVersion, before);
        if (updated != 1) throw conflict(expectedVersion + 1);
        if ("REPORT_EXCEPTION".equals(action)) warnings.recordEventWarning("DELIVERY_EXCEPTION", "HIGH", "DELIVERY_NOTICE", id,
                "到货通知" + id + "出现运输异常", note, "联系供应商确认影响范围、预计恢复时间和替代交付安排");
        if ("RESUME".equals(action)) warnings.resolveEventWarning("DELIVERY_EXCEPTION", "DELIVERY_NOTICE", id, "供应商已记录恢复运输：" + note);
        audit.log(requestId, "NOTICE_" + action, "DELIVERY_NOTICE", id, before, after, note);
        return notice(id);
    }

    public List<Map<String, Object>> receipts() {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select r.id,r.receipt_no,r.order_id,o.order_no,o.version order_version,s.supplier_name,w.warehouse_name,r.notice_id,r.status,r.received_at,r.notes,r.created_at," +
                "coalesce(sum(ri.ordered_qty),0) expected_quantity,coalesce(sum(ri.received_qty),0) received_quantity,coalesce(sum(ri.received_qty-ri.ordered_qty),0) difference " +
                "from receipt r join purchase_order o on o.id=r.order_id join supplier s on s.id=o.supplier_id join warehouse w on w.id=r.warehouse_id left join receipt_item ri on ri.receipt_id=r.id";
        String group = " group by r.id,r.receipt_no,r.order_id,o.order_no,o.version,s.supplier_name,w.warehouse_name,r.notice_id,r.status,r.received_at,r.notes,r.created_at";
        return "SUPPLIER".equals(user.role()) ? jdbc.queryForList(sql + " where o.supplier_id=?" + group + " order by r.id desc", user.supplierId()) : jdbc.queryForList(sql + group + " order by r.id desc");
    }

    @Transactional
    public Map<String, Object> receive(String requestId, ReceiptInput input, String idempotencyKey) {
        AuthUser user = SecuritySupport.currentUser();
        requireBuyer(user);
        if (blank(idempotencyKey)) throw new BusinessException("IDEMPOTENCY_REQUIRED", "收货过账必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> prior = jdbc.queryForList("select id from receipt where idempotency_key=?", idempotencyKey);
        if (!prior.isEmpty()) return receipt(((Number) prior.get(0).get("id")).longValue());
        if (input.items() == null || input.items().isEmpty()) throw rule("收货必须至少包含一个明细");
        Set<Long> itemIds = new HashSet<>();
        for (ReceiptItemInput item : input.items()) if (!itemIds.add(item.orderItemId())) throw rule("收货不能重复提交同一订单明细");
        Map<String, Object> order = one("select id,status,version,supplier_id,plan_id,order_amount from purchase_order where id=? for update", input.orderId(), "采购订单不存在");
        String orderStatus = order.get("status").toString();
        int orderVersion = ((Number) order.get("version")).intValue();
        if (orderVersion != input.orderVersion()) throw conflict(orderVersion);
        if (!List.of("ARRIVED", "PARTIALLY_RECEIVED").contains(orderStatus)) throw state(orderStatus);
        Long warehouseId = codeId("warehouse", "warehouse_code", input.warehouseCode(), "仓库不存在");
        Long noticeId = input.deliveryNoticeId();
        if (noticeId == null) {
            List<Long> arrived = jdbc.query("select id from delivery_notice where order_id=? and status='ARRIVED' order by id", (rs, n) -> rs.getLong(1), input.orderId());
            if (arrived.size() != 1) throw rule(arrived.isEmpty() ? "收货必须关联已到达的到货通知" : "存在多个已到达通知，请明确选择本次收货通知");
            noticeId = arrived.get(0);
        }
        Map<String, Object> notice = one("select order_id,status from delivery_notice where id=?", noticeId, "到货通知不存在");
        if (((Number) notice.get("order_id")).longValue() != input.orderId() || !"ARRIVED".equals(notice.get("status"))) throw rule("到货通知与订单不匹配或尚未到达");
        String receiptNo = number("RCV");
        jdbc.update("insert into receipt(receipt_no,order_id,notice_id,warehouse_id,status,received_by,received_at,notes,idempotency_key) values(?,?,?,?,?,?,?,?,?)",
                receiptNo, input.orderId(), noticeId, warehouseId, "COMPLETED", user.id(),
                input.receivedAt() == null ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : java.sql.Timestamp.from(input.receivedAt().toInstant()), input.notes(), idempotencyKey);
        Long receiptId = jdbc.queryForObject("select id from receipt where receipt_no=?", Long.class, receiptNo);
        boolean variance = false;
        StringBuilder varianceDetail = new StringBuilder();
        for (ReceiptItemInput item : input.items()) {
            Map<String, Object> oi = one("select id,material_id,quantity,received_qty,unit_price from purchase_order_item where id=? for update", item.orderItemId(), "订单明细不存在");
            Number belongs = jdbc.queryForObject("select count(*) from purchase_order_item where id=? and order_id=?", Number.class, item.orderItemId(), input.orderId());
            if (belongs == null || belongs.longValue() == 0) throw rule("收货明细不属于当前订单");
            List<Map<String,Object>> noticeItems = jdbc.queryForList("select quantity from delivery_notice_item where notice_id=? and order_item_id=?", noticeId, item.orderItemId());
            if (noticeItems.isEmpty()) throw rule("收货明细不属于所选到货通知");
            BigDecimal ordered = decimal(oi.get("quantity"));
            BigDecimal already = decimal(oi.get("received_qty"));
            BigDecimal received = item.receivedQty();
            BigDecimal qualified = item.qualifiedQty();
            BigDecimal rejected = item.rejectedQty();
            if (received == null || qualified == null || rejected == null || received.signum() <= 0 || qualified.signum() < 0 || rejected.signum() < 0) throw rule("收货数量必须合法且不能为负数");
            if (received.signum() <= 0 || received.compareTo(ordered.subtract(already)) > 0) throw rule("实收数量必须大于0且不能超过订单剩余数量");
            if (qualified.add(rejected).compareTo(received) != 0) throw rule("合格数量与拒收数量之和必须等于实收数量");
            BigDecimal noticeQty = decimal(noticeItems.get(0).get("quantity"));
            BigDecimal noticeQualified = jdbc.queryForObject("select coalesce(sum(ri.qualified_qty),0) from receipt_item ri join receipt r on r.id=ri.receipt_id where r.notice_id=? and ri.order_item_id=?", BigDecimal.class, noticeId, item.orderItemId());
            if (noticeQualified == null) noticeQualified = BigDecimal.ZERO;
            BigDecimal expectedThisReceipt = noticeQty.subtract(noticeQualified);
            if (expectedThisReceipt.signum() <= 0) throw rule("所选到货通知明细已全部完成，不能重复收货");
            if (received.compareTo(expectedThisReceipt) > 0) throw rule("实收数量不能超过所选到货通知的剩余数量");
            if (qualified.compareTo(expectedThisReceipt) > 0) throw rule("合格数量不能超过所选到货通知的剩余数量");
            if ((rejected.signum() > 0 || received.compareTo(expectedThisReceipt) != 0) && blank(item.varianceReason())) throw rule("数量或质量存在差异时必须填写原因");
            BigDecimal difference = received.subtract(expectedThisReceipt);
            jdbc.update("insert into receipt_item(receipt_id,order_item_id,material_id,ordered_qty,received_qty,qualified_qty,rejected_qty,difference_qty,difference_reason) values(?,?,?,?,?,?,?,?,?)",
                    receiptId, item.orderItemId(), oi.get("material_id"), expectedThisReceipt, received, qualified, rejected, difference, item.varianceReason());
            jdbc.update("update purchase_order_item set received_qty=received_qty+? where id=?", qualified, item.orderItemId());
            if (qualified.signum() > 0) postInventory(requestId, warehouseId, ((Number) oi.get("material_id")).longValue(), qualified, receiptId, user.id());
            if (rejected.signum() > 0 || difference.signum() != 0) {
                variance = true;
                if (!varianceDetail.isEmpty()) varianceDetail.append("；");
                varianceDetail.append("订单明细").append(item.orderItemId()).append("实收").append(received).append("、合格").append(qualified).append("、拒收").append(rejected);
            }
        }
        Number remainingItems = jdbc.queryForObject("select count(*) from purchase_order_item where order_id=? and received_qty<quantity", Number.class, input.orderId());
        String after = remainingItems != null && remainingItems.longValue() == 0 ? "RECEIVED" : "PARTIALLY_RECEIVED";
        int updated = jdbc.update("update purchase_order set status=?,version=version+1,updated_at=current_timestamp where id=? and version=? and status=?", after, input.orderId(), input.orderVersion(), orderStatus);
        if (updated != 1) throw conflict(input.orderVersion() + 1);
        Number noticeRemaining = jdbc.queryForObject("select count(*) from delivery_notice_item ni where ni.notice_id=? and (select coalesce(sum(ri.qualified_qty),0) from receipt_item ri join receipt r on r.id=ri.receipt_id where r.notice_id=ni.notice_id and ri.order_item_id=ni.order_item_id)<ni.quantity", Number.class, noticeId);
        if (noticeRemaining != null && noticeRemaining.longValue() == 0) jdbc.update("update delivery_notice set status='RECEIVED',version=version+1,updated_at=current_timestamp where id=? and status='ARRIVED'", noticeId);
        if (variance) warnings.recordEventWarning("RECEIPT_VARIANCE", "HIGH", "RECEIPT", receiptId,
                "收货单" + receiptNo + "存在数量或质量差异", varianceDetail.toString(), "复核拒收或短收原因，未合格数量仍需后续通知补足");
        warnings.refreshRuleWarnings();
        audit.log(requestId, "POST_RECEIPT", "RECEIPT", receiptId, null, "COMPLETED", receiptNo);
        audit.log(requestId, "ORDER_" + after, "PURCHASE_ORDER", input.orderId(), orderStatus, after, receiptNo);
        return receipt(receiptId);
    }

    public Map<String, Object> receipt(long id) {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select r.id,r.receipt_no,r.order_id,o.order_no,o.version order_version,o.supplier_id,s.supplier_name,r.notice_id,w.warehouse_code,w.warehouse_name,r.status,r.received_at,r.notes,r.created_at from receipt r join purchase_order o on o.id=r.order_id join supplier s on s.id=o.supplier_id join warehouse w on w.id=r.warehouse_id where r.id=?";
        List<Map<String, Object>> rows = "SUPPLIER".equals(user.role()) ? jdbc.queryForList(sql + " and o.supplier_id=?", id, user.supplierId()) : jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw notFound("收货单不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("select ri.id,ri.order_item_id,m.material_code,m.material_name,m.unit,ri.ordered_qty,ri.received_qty,ri.qualified_qty,ri.rejected_qty,ri.difference_qty,ri.difference_reason from receipt_item ri join material m on m.id=ri.material_id where ri.receipt_id=? order by ri.id", id));
        return result;
    }

    public List<Map<String, Object>> reconciliations() {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select r.id,r.reconciliation_no,o.order_no,s.supplier_name,r.status,r.order_amount,r.received_amount,r.difference_amount,r.dispute_reason,r.resolution_note,r.buyer_confirmed,r.supplier_confirmed,r.version,r.updated_at from reconciliation r join purchase_order o on o.id=r.order_id join supplier s on s.id=o.supplier_id";
        return "SUPPLIER".equals(user.role()) ? jdbc.queryForList(sql + " where o.supplier_id=? order by r.id desc", user.supplierId()) : jdbc.queryForList(sql + " order by r.id desc");
    }

    @Transactional
    public Map<String, Object> createReconciliation(String requestId, long orderId, int expectedOrderVersion, String idempotencyKey) {
        AuthUser user = SecuritySupport.currentUser();
        requireBuyer(user);
        if (blank(idempotencyKey)) throw new BusinessException("IDEMPOTENCY_REQUIRED", "创建对账必须提供 Idempotency-Key", HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> prior = jdbc.queryForList("select id from reconciliation where order_id=?", orderId);
        if (!prior.isEmpty()) return reconciliation(((Number) prior.get(0).get("id")).longValue());
        Map<String, Object> order = orderRow(orderId);
        String state = order.get("status").toString();
        int version = ((Number) order.get("version")).intValue();
        if (version != expectedOrderVersion) throw conflict(version);
        require(state, "RECEIVED");
        BigDecimal orderAmount = decimal(order.get("order_amount"));
        BigDecimal receivedAmount = jdbc.queryForObject("select coalesce(sum(ri.qualified_qty*oi.unit_price),0) from receipt_item ri join receipt r on r.id=ri.receipt_id join purchase_order_item oi on oi.id=ri.order_item_id where r.order_id=?", BigDecimal.class, orderId);
        if (receivedAmount == null) receivedAmount = BigDecimal.ZERO;
        BigDecimal difference = receivedAmount.subtract(orderAmount);
        String no = number("REC");
        jdbc.update("insert into reconciliation(reconciliation_no,order_id,status,order_amount,received_amount,difference_amount,buyer_confirmed) values(?,?, 'PENDING',?,?,?,true)", no, orderId, orderAmount, receivedAmount, difference);
        Long id = jdbc.queryForObject("select id from reconciliation where reconciliation_no=?", Long.class, no);
        for (Map<String, Object> oi : jdbc.queryForList("select id,quantity,received_qty,unit_price from purchase_order_item where order_id=?", orderId)) {
            BigDecimal qty = decimal(oi.get("quantity"));
            BigDecimal received = decimal(oi.get("received_qty"));
            BigDecimal price = decimal(oi.get("unit_price"));
            jdbc.update("insert into reconciliation_item(reconciliation_id,order_item_id,ordered_qty,received_qty,unit_price,difference_amount) values(?,?,?,?,?,?)", id, oi.get("id"), qty, received, price, received.subtract(qty).multiply(price));
        }
        int updated = jdbc.update("update purchase_order set status='RECONCILING',version=version+1,updated_at=current_timestamp where id=? and version=? and status='RECEIVED'", orderId, expectedOrderVersion);
        if (updated != 1) throw conflict(expectedOrderVersion + 1);
        if (difference.signum() != 0) warnings.recordEventWarning("RECONCILIATION_DIFFERENCE", "HIGH", "RECONCILIATION", id,
                "对账单" + no + "存在金额差异", "订单金额" + orderAmount + "，合格收货金额" + receivedAmount + "，差异" + difference,
                "采购方与供应商核对收货差异并在完成对账前记录处理方案");
        audit.log(requestId, "CREATE_RECONCILIATION", "RECONCILIATION", id, null, "PENDING", no + ";" + idempotencyKey);
        audit.log(requestId, "ORDER_RECONCILING", "PURCHASE_ORDER", orderId, "RECEIVED", "RECONCILING", no);
        return reconciliation(id);
    }

    public Map<String, Object> reconciliation(long id) {
        AuthUser user = SecuritySupport.currentUser();
        String sql = "select r.id,r.reconciliation_no,r.order_id,o.order_no,o.plan_id,o.supplier_id,s.supplier_name,r.status,r.order_amount,r.received_amount,r.difference_amount,r.dispute_reason,r.resolution_note,r.buyer_confirmed,r.supplier_confirmed,r.version,r.created_at,r.updated_at from reconciliation r join purchase_order o on o.id=r.order_id join supplier s on s.id=o.supplier_id where r.id=?";
        List<Map<String, Object>> rows = "SUPPLIER".equals(user.role()) ? jdbc.queryForList(sql + " and o.supplier_id=?", id, user.supplierId()) : jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw notFound("对账单不存在");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("items", jdbc.queryForList("select ri.id,ri.order_item_id,m.material_code,m.material_name,m.unit,ri.ordered_qty,ri.received_qty,ri.unit_price,ri.difference_amount from reconciliation_item ri join purchase_order_item oi on oi.id=ri.order_item_id join material m on m.id=oi.material_id where ri.reconciliation_id=? order by ri.id", id));
        return result;
    }

    @Transactional
    public Map<String, Object> transitionReconciliation(String requestId, long id, int expectedVersion, String rawAction, String note) {
        AuthUser user = SecuritySupport.currentUser();
        Map<String, Object> row = one("select r.status,r.version,r.order_id,r.reconciliation_no,r.dispute_reason,r.resolution_note,o.supplier_id,o.plan_id from reconciliation r join purchase_order o on o.id=r.order_id where r.id=?", id, "对账单不存在");
        long supplierId = ((Number) row.get("supplier_id")).longValue();
        if ("SUPPLIER".equals(user.role()) && (user.supplierId() == null || supplierId != user.supplierId())) throw notFound("对账单不存在");
        String before = row.get("status").toString();
        int version = ((Number) row.get("version")).intValue();
        if (version != expectedVersion) throw conflict(version);
        String action = rawAction.toUpperCase(Locale.ROOT);
        String after;
        switch (action) {
            case "CONFIRM" -> { requireSupplier(user); require(before, "PENDING"); after = "CONFIRMED"; }
            case "DISPUTE" -> { requireSupplier(user); require(before, "PENDING"); if (blank(note)) throw rule("提出争议必须填写原因"); after = "DISPUTED"; }
            case "RESOLVE" -> { requireBuyer(user); require(before, "DISPUTED"); if (blank(note)) throw rule("解决争议必须填写方案"); after = "RESOLVED"; }
            case "COMPLETE" -> { requireBuyer(user); if (!List.of("CONFIRMED", "RESOLVED").contains(before)) throw state(before); after = "COMPLETED"; }
            default -> throw rule("未知对账动作: " + rawAction);
        }
        int updated = jdbc.update("update reconciliation set status=?,supplier_confirmed=?,dispute_reason=?,resolution_note=?,version=version+1,updated_at=current_timestamp where id=? and version=? and status=?",
                after, "CONFIRMED".equals(after), "DISPUTED".equals(after) ? note : row.get("dispute_reason"), "RESOLVED".equals(after) ? note : row.get("resolution_note"), id, expectedVersion, before);
        if (updated != 1) throw conflict(expectedVersion + 1);
        if ("COMPLETED".equals(after)) {
            long orderId = ((Number) row.get("order_id")).longValue();
            jdbc.update("update purchase_order set status='COMPLETED',version=version+1,updated_at=current_timestamp where id=? and status='RECONCILING'", orderId);
            jdbc.update("update purchase_plan set status='CLOSED',version=version+1,updated_at=current_timestamp where id=? and status='ORDER_CREATED'", row.get("plan_id"));
            refreshSupplierPerformance(supplierId);
            warnings.resolveEventWarning("RECONCILIATION_DIFFERENCE", "RECONCILIATION", id, "对账已完成并记录双方确认或解决方案");
            warnings.refreshRuleWarnings();
            audit.log(requestId, "ORDER_COMPLETED", "PURCHASE_ORDER", orderId, "RECONCILING", "COMPLETED", row.get("reconciliation_no") == null ? null : row.get("reconciliation_no").toString());
        }
        audit.log(requestId, "RECONCILIATION_" + action, "RECONCILIATION", id, before, after, note);
        return reconciliation(id);
    }

    private void syncOrder(String requestId, long orderId, List<String> allowed, String after, String action) {
        Map<String, Object> order = orderRow(orderId);
        String before = order.get("status").toString();
        if (!allowed.contains(before)) throw state(before);
        if ("PARTIALLY_RECEIVED".equals(before)) {
            audit.log(requestId, action, "PURCHASE_ORDER", orderId, before, before, "分批履约进度由到货通知记录，订单保留部分收货状态");
            return;
        }
        int updated = jdbc.update("update purchase_order set status=?,version=version+1,updated_at=current_timestamp where id=? and status=?", after, orderId, before);
        if (updated != 1) throw conflict(((Number) order.get("version")).intValue() + 1);
        audit.log(requestId, action, "PURCHASE_ORDER", orderId, before, after, null);
    }

    private void postInventory(String requestId, long warehouseId, long materialId, BigDecimal qty, long receiptId, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,on_hand_qty from inventory where warehouse_id=? and material_id=? for update", warehouseId, materialId);
        BigDecimal before = rows.isEmpty() ? BigDecimal.ZERO : decimal(rows.get(0).get("on_hand_qty"));
        BigDecimal after = before.add(qty);
        if (rows.isEmpty()) jdbc.update("insert into inventory(warehouse_id,material_id,on_hand_qty,reserved_qty,in_transit_qty) values(?,?,?,0,0)", warehouseId, materialId, after);
        else jdbc.update("update inventory set on_hand_qty=?,version=version+1,updated_at=current_timestamp where id=?", after, rows.get(0).get("id"));
        String no = number("IT");
        jdbc.update("insert into inventory_transaction(transaction_no,warehouse_id,material_id,transaction_type,quantity,before_qty,after_qty,source_type,source_id,operator_id) values(?,?,?,?,?,?,?,?,?,?)",
                no, warehouseId, materialId, "RECEIPT", qty, before, after, "RECEIPT", receiptId, userId);
        audit.log(requestId, "INVENTORY_RECEIPT", "INVENTORY", materialId, before.toPlainString(), after.toPlainString(), no);
    }

    private void refreshSupplierPerformance(long supplierId) {
        List<Map<String,Object>> rows = jdbc.queryForList("""
                select o.id,o.expected_arrival_date,max(r.received_at) actual_arrival
                from purchase_order o join receipt r on r.order_id=o.id
                where o.supplier_id=?
                group by o.id,o.expected_arrival_date
                """, supplierId);
        if (rows.isEmpty()) return;
        long onTime = rows.stream().filter(row -> {
            if (row.get("expected_arrival_date") == null || row.get("actual_arrival") == null) return false;
            java.time.LocalDate expected = row.get("expected_arrival_date") instanceof java.sql.Date d ? d.toLocalDate() : java.time.LocalDate.parse(row.get("expected_arrival_date").toString());
            java.time.LocalDate actual = row.get("actual_arrival") instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate() : java.time.OffsetDateTime.parse(row.get("actual_arrival").toString()).toLocalDate();
            return !actual.isAfter(expected);
        }).count();
        BigDecimal rate = BigDecimal.valueOf(onTime).divide(BigDecimal.valueOf(rows.size()), 4, java.math.RoundingMode.HALF_UP);
        jdbc.update("update supplier set on_time_rate=?,version=version+1,updated_at=current_timestamp where id=?", rate, supplierId);
    }

    private Map<String, Object> orderRow(long id) { return one("select id,status,version,supplier_id,plan_id,order_amount from purchase_order where id=?", id, "采购订单不存在"); }
    private Map<String, Object> one(String sql, long id, String message) { List<Map<String,Object>> rows=jdbc.queryForList(sql,id); if(rows.isEmpty()) throw notFound(message); return rows.get(0); }
    private Long codeId(String table, String codeColumn, String code, String message) { List<Long> ids=jdbc.query("select id from "+table+" where "+codeColumn+"=? and status='ACTIVE'",(rs,n)->rs.getLong(1),code); if(ids.isEmpty()) throw rule(message+": "+code); return ids.get(0); }
    private static void require(String actual, String expected) { if(!expected.equals(actual)) throw state(actual); }
    private static void requireSupplier(AuthUser user) { if(!"SUPPLIER".equals(user.role())) throw forbidden("只有供应商可执行该动作"); }
    private static void requireBuyer(AuthUser user) { if(!"BUYER".equals(user.role())) throw forbidden("只有采购协同人员可执行该动作"); }
    private static BusinessException state(String actual) { return new BusinessException("ILLEGAL_STATE_TRANSITION","当前状态不允许执行该动作",HttpStatus.CONFLICT,Map.of("currentState",actual)); }
    private static BusinessException conflict(int actual) { return new BusinessException("VERSION_CONFLICT","数据已更新，请刷新后重试",HttpStatus.CONFLICT,Map.of("currentVersion",actual)); }
    private static BusinessException notFound(String m) { return new BusinessException("RESOURCE_NOT_FOUND",m,HttpStatus.NOT_FOUND); }
    private static BusinessException forbidden(String m) { return new BusinessException("FORBIDDEN",m,HttpStatus.FORBIDDEN); }
    private static BusinessException rule(String m) { return new BusinessException("BUSINESS_RULE_VIOLATION",m,HttpStatus.UNPROCESSABLE_ENTITY); }
    private static boolean blank(String s) { return s==null||s.isBlank(); }
    private static BigDecimal decimal(Object v) { return v instanceof BigDecimal b?b:new BigDecimal(v.toString()); }
    private static String number(String p) { return p+"-"+java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))+"-"+UUID.randomUUID().toString().substring(0,5).toUpperCase(); }

    public record NoticeItemInput(Long orderItemId, BigDecimal quantity) {}
    public record NoticeInput(Long orderId, OffsetDateTime expectedArrivalAt, String carrierName, String trackingNo, List<NoticeItemInput> items) {}
    public record ReceiptItemInput(Long orderItemId, BigDecimal receivedQty, BigDecimal qualifiedQty, BigDecimal rejectedQty, String varianceReason) {}
    public record ReceiptInput(Long orderId, Long deliveryNoticeId, int orderVersion, String warehouseCode, OffsetDateTime receivedAt, String notes, List<ReceiptItemInput> items) {}
}
