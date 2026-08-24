package com.scic.platform.masterdata;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.BusinessException;
import com.scic.platform.common.RequestIds;
import com.scic.platform.system.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('BUYER','MANAGER')")
public class MasterDataController {
    private final SupplierMapper supplierMapper;
    private final MaterialMapper materialMapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MasterDataController(SupplierMapper supplierMapper, MaterialMapper materialMapper, JdbcTemplate jdbc, AuditService audit) {
        this.supplierMapper = supplierMapper;
        this.materialMapper = materialMapper;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierEntity>> suppliers(HttpServletRequest request) {
        return ApiResponse.ok(supplierMapper.selectList(new QueryWrapper<SupplierEntity>().orderByAsc("supplier_code")), RequestIds.get(request));
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<SupplierEntity> addSupplier(@Valid @RequestBody SupplierInput input, HttpServletRequest request) {
        SupplierEntity entity = new SupplierEntity();
        entity.setSupplierCode(input.supplierCode());
        entity.setSupplierName(input.supplierName());
        entity.setContactName(input.contactName());
        entity.setContactPhone(input.contactPhone());
        entity.setLevelCode(input.levelCode() == null ? "B" : input.levelCode());
        entity.setStatus("ACTIVE");
        entity.setOnTimeRate(BigDecimal.ZERO);
        entity.setVersion(0);
        supplierMapper.insert(entity);
        audit.log(RequestIds.get(request), "CREATE_SUPPLIER", "SUPPLIER", entity.getId(), null, "ACTIVE", input.supplierCode());
        return ApiResponse.ok(entity, RequestIds.get(request));
    }

    @GetMapping("/materials")
    public ApiResponse<List<MaterialEntity>> materials(HttpServletRequest request) {
        return ApiResponse.ok(materialMapper.selectList(new QueryWrapper<MaterialEntity>().orderByAsc("material_code")), RequestIds.get(request));
    }

    @PostMapping("/materials")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<MaterialEntity> addMaterial(@Valid @RequestBody MaterialInput input, HttpServletRequest request) {
        MaterialEntity entity = new MaterialEntity();
        entity.setMaterialCode(input.materialCode());
        entity.setMaterialName(input.materialName());
        entity.setCategory(input.category());
        entity.setUnit(input.unit());
        entity.setSafetyStock(input.safetyStock());
        entity.setMinOrderQty(input.minOrderQty());
        entity.setPackSize(input.packSize());
        entity.setLeadTimeDays(input.leadTimeDays());
        entity.setStandardPrice(input.standardPrice());
        entity.setStatus("ACTIVE");
        entity.setVersion(0);
        materialMapper.insert(entity);
        audit.log(RequestIds.get(request), "CREATE_MATERIAL", "MATERIAL", entity.getId(), null, "ACTIVE", input.materialCode());
        return ApiResponse.ok(entity, RequestIds.get(request));
    }

    @GetMapping("/warehouses")
    public ApiResponse<List<Map<String, Object>>> warehouses(HttpServletRequest request) {
        return ApiResponse.ok(jdbc.queryForList("select id,warehouse_code,warehouse_name,location_text,status,version,created_at,updated_at from warehouse order by warehouse_code"), RequestIds.get(request));
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String, Object>> addWarehouse(@Valid @RequestBody WarehouseCreate input, HttpServletRequest request) {
        jdbc.update("insert into warehouse(warehouse_code,warehouse_name,location_text,status) values(?,?,?,'ACTIVE')",
                input.warehouseCode(), input.warehouseName(), input.locationText());
        Long id = jdbc.queryForObject("select id from warehouse where warehouse_code=?", Long.class, input.warehouseCode());
        audit.log(RequestIds.get(request), "CREATE_WAREHOUSE", "WAREHOUSE", id, null, "ACTIVE", input.warehouseCode());
        return ApiResponse.ok(warehouse(id), RequestIds.get(request));
    }

    @PatchMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('BUYER')")
    public ApiResponse<Map<String, Object>> updateWarehouse(@PathVariable long id,
                                                            @Valid @RequestBody WarehouseUpdate input,
                                                            HttpServletRequest request) {
        Map<String, Object> before = warehouse(id);
        int currentVersion = ((Number) before.get("version")).intValue();
        if (currentVersion != input.expectedVersion()) {
            throw new BusinessException("VERSION_CONFLICT", "仓库信息已更新，请刷新后重试", HttpStatus.CONFLICT,
                    Map.of("currentVersion", currentVersion));
        }
        String name = input.warehouseName() == null || input.warehouseName().isBlank()
                ? before.get("warehouse_name").toString() : input.warehouseName();
        String location = input.locationText() == null ? (String) before.get("location_text") : input.locationText();
        String status = input.status() == null ? before.get("status").toString() : input.status().toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE").contains(status)) {
            throw new BusinessException("VALIDATION_ERROR", "仓库状态仅允许 ACTIVE 或 INACTIVE", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int updated = jdbc.update("update warehouse set warehouse_name=?,location_text=?,status=?,version=version+1,updated_at=current_timestamp where id=? and version=?",
                name, location, status, id, input.expectedVersion());
        if (updated != 1) throw new BusinessException("VERSION_CONFLICT", "仓库信息已更新，请刷新后重试", HttpStatus.CONFLICT);
        audit.log(RequestIds.get(request), "UPDATE_WAREHOUSE", "WAREHOUSE", id, before.get("status").toString(), status, name);
        return ApiResponse.ok(warehouse(id), RequestIds.get(request));
    }

    @GetMapping("/inventory")
    public ApiResponse<List<Map<String, Object>>> inventory(HttpServletRequest request) {
        String sql = "select i.id, w.warehouse_code, w.warehouse_name, m.material_code, m.material_name, m.unit, i.on_hand_qty, i.reserved_qty, (i.on_hand_qty-i.reserved_qty) available_qty, i.in_transit_qty, m.safety_stock, case when (i.on_hand_qty-i.reserved_qty+i.in_transit_qty) < m.safety_stock then 'SHORTAGE' else 'NORMAL' end risk_status, i.version, i.updated_at from inventory i join warehouse w on w.id=i.warehouse_id join material m on m.id=i.material_id order by w.warehouse_code,m.material_code";
        return ApiResponse.ok(jdbc.queryForList(sql), RequestIds.get(request));
    }

    public record SupplierInput(@NotBlank String supplierCode, @NotBlank String supplierName,
                                String contactName, String contactPhone, String levelCode) {}

    public record MaterialInput(@NotBlank String materialCode, @NotBlank String materialName,
                                @NotBlank String category, @NotBlank String unit,
                                @NotNull @DecimalMin("0") BigDecimal safetyStock,
                                @NotNull @DecimalMin("0.0001") BigDecimal minOrderQty,
                                @NotNull @DecimalMin("0.0001") BigDecimal packSize,
                                @NotNull @Min(1) Integer leadTimeDays,
                                @NotNull @DecimalMin("0") BigDecimal standardPrice) {}

    public record WarehouseCreate(@NotBlank String warehouseCode, @NotBlank String warehouseName,
                                  String locationText) {}
    public record WarehouseUpdate(String warehouseName, String locationText, String status,
                                  @NotNull Integer expectedVersion) {}

    private Map<String, Object> warehouse(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,warehouse_code,warehouse_name,location_text,status,version,created_at,updated_at from warehouse where id=?", id);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "仓库不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
}
