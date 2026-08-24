package com.scic.platform.system;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.BusinessException;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AdminController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping("/roles")
    public ApiResponse<List<Map<String,Object>>> roles(HttpServletRequest request) {
        return ApiResponse.ok(jdbc.queryForList("select id,role_code,role_name,created_at from sys_role order by id"), RequestIds.get(request));
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String,Object>>> users(HttpServletRequest request) {
        return ApiResponse.ok(jdbc.queryForList("select u.id,u.username,u.display_name,u.role_code,u.supplier_id,s.supplier_name,u.enabled,u.last_login_at,u.version,u.created_at from sys_user u left join supplier s on s.id=u.supplier_id order by u.id"), RequestIds.get(request));
    }

    @PostMapping("/users")
    public ApiResponse<Map<String,Object>> create(@Valid @RequestBody UserCreate input, HttpServletRequest request) {
        validateRole(input.roleCode(), input.supplierId());
        jdbc.update("insert into sys_user(username,display_name,password_hash,role_code,supplier_id,enabled) values(?,?,?,?,?,true)",
                input.username(), input.displayName(), encoder.encode(input.password()), input.roleCode(), input.supplierId());
        Long id = jdbc.queryForObject("select id from sys_user where username=?", Long.class, input.username());
        audit.log(RequestIds.get(request), "CREATE_USER", "SYS_USER", id, null, "ENABLED", input.username());
        return ApiResponse.ok(user(id), RequestIds.get(request));
    }

    @PatchMapping("/users/{id}")
    public ApiResponse<Map<String,Object>> update(@PathVariable long id, @Valid @RequestBody UserUpdate input, HttpServletRequest request) {
        Map<String,Object> before = user(id);
        int currentVersion = ((Number)before.get("version")).intValue();
        if (currentVersion != input.expectedVersion()) throw new BusinessException("VERSION_CONFLICT", "用户信息已更新，请刷新后重试", HttpStatus.CONFLICT, Map.of("currentVersion", currentVersion));
        String role = input.roleCode() == null ? before.get("role_code").toString() : input.roleCode();
        Long supplierId = input.roleCode() == null && input.supplierId() == null ? before.get("supplier_id") == null ? null : ((Number)before.get("supplier_id")).longValue() : input.supplierId();
        validateRole(role, supplierId);
        boolean enabled = input.enabled() == null ? Boolean.TRUE.equals(before.get("enabled")) : input.enabled();
        String displayName = input.displayName() == null || input.displayName().isBlank() ? before.get("display_name").toString() : input.displayName();
        int updated = jdbc.update("update sys_user set display_name=?,role_code=?,supplier_id=?,enabled=?,version=version+1 where id=? and version=?", displayName, role, supplierId, enabled, id, input.expectedVersion());
        if (updated != 1) throw new BusinessException("VERSION_CONFLICT", "用户信息已更新，请刷新后重试", HttpStatus.CONFLICT);
        audit.log(RequestIds.get(request), "UPDATE_USER", "SYS_USER", id, before.get("enabled").toString(), Boolean.toString(enabled), role);
        return ApiResponse.ok(user(id), RequestIds.get(request));
    }

    private Map<String,Object> user(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("select u.id,u.username,u.display_name,u.role_code,u.supplier_id,s.supplier_name,u.enabled,u.last_login_at,u.version,u.created_at from sys_user u left join supplier s on s.id=u.supplier_id where u.id=?", id);
        if (rows.isEmpty()) throw new BusinessException("RESOURCE_NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        return rows.get(0);
    }

    private void validateRole(String role, Long supplierId) {
        Number count = jdbc.queryForObject("select count(*) from sys_role where role_code=?", Number.class, role);
        if (count == null || count.longValue() == 0) throw new BusinessException("VALIDATION_ERROR", "角色不存在", HttpStatus.UNPROCESSABLE_ENTITY);
        if ("SUPPLIER".equals(role) && supplierId == null) throw new BusinessException("VALIDATION_ERROR", "供应商角色必须绑定供应商", HttpStatus.UNPROCESSABLE_ENTITY);
        if (!"SUPPLIER".equals(role) && supplierId != null) throw new BusinessException("VALIDATION_ERROR", "非供应商角色不能绑定供应商", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public record UserCreate(@NotBlank String username,@NotBlank String displayName,@NotBlank @Size(min=8,max=72) String password,@NotBlank String roleCode,Long supplierId){}
    public record UserUpdate(String displayName,String roleCode,Long supplierId,Boolean enabled,@NotNull Integer expectedVersion){}
}
