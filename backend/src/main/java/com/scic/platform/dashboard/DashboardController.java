package com.scic.platform.dashboard;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.RequestIds;
import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(HttpServletRequest request) {
        AuthUser user = SecuritySupport.currentUser();
        boolean supplier = "SUPPLIER".equals(user.role());
        String supplierWhere = supplier ? " where o.supplier_id=" + user.supplierId() : "";

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("openWarnings", supplier ? 0 : number("select count(*) from warning_record where status in ('OPEN','ACKNOWLEDGED')"));
        metrics.put("highWarnings", supplier ? 0 : number("select count(*) from warning_record where status='OPEN' and severity='HIGH'"));
        metrics.put("activeOrders", number("select count(*) from purchase_order o" + supplierWhere + (supplier ? " and" : " where") + " o.status not in ('COMPLETED','CANCELLED','REJECTED')"));
        metrics.put("pendingPlans", supplier ? 0 : number("select count(*) from purchase_plan where status='PENDING_APPROVAL'"));
        metrics.put("inventoryShortages", supplier ? 0 : number("select count(*) from inventory i join material m on m.id=i.material_id where i.on_hand_qty-i.reserved_qty+i.in_transit_qty < m.safety_stock"));
        metrics.put("orderAmount", money("select coalesce(sum(o.order_amount),0) from purchase_order o" + supplierWhere));
        result.put("metrics", metrics);

        String orderStatusSql = "select status, count(*) count from purchase_order o" + supplierWhere + " group by status order by status";
        result.put("orderStatus", jdbc.queryForList(orderStatusSql));
        result.put("warningDistribution", supplier ? List.of() : jdbc.queryForList("select severity, count(*) count from warning_record where status<>'CLOSED' group by severity order by case severity when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end"));
        result.put("inventoryRisk", supplier ? List.of() : jdbc.queryForList("select m.material_code, m.material_name, sum(i.on_hand_qty-i.reserved_qty+i.in_transit_qty) available_supply, m.safety_stock from inventory i join material m on m.id=i.material_id group by m.id,m.material_code,m.material_name,m.safety_stock order by (sum(i.on_hand_qty-i.reserved_qty+i.in_transit_qty)-m.safety_stock) asc limit 5"));
        result.put("recentOrders", jdbc.queryForList("select o.id,o.order_no,s.supplier_name,o.status,o.order_amount,o.expected_arrival_date,o.version,o.updated_at from purchase_order o join supplier s on s.id=o.supplier_id" + supplierWhere + " order by o.updated_at desc limit 8"));
        result.put("dataNotice", "当前展示数据标记为 DEMO_SYNTHETIC，用于系统演示与功能测试，不代表真实企业生产数据。指标统计时间以数据库当前快照为准。");
        result.put("asOf", java.time.OffsetDateTime.now());
        return ApiResponse.ok(result, RequestIds.get(request));
    }

    private long number(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }
}
