package com.scic.platform.security;

import com.scic.platform.common.ApiResponse;
import com.scic.platform.common.BusinessException;
import com.scic.platform.common.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest input, HttpServletRequest request) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, username, display_name, role_code, supplier_id, password_hash, enabled from sys_user where username = ?",
                input.username());
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("enabled"))) {
            throw new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> row = rows.get(0);
        if (!passwordEncoder.matches(input.password(), row.get("password_hash").toString())) {
            throw new BusinessException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        Number supplierId = (Number) row.get("supplier_id");
        AuthUser user = new AuthUser(((Number) row.get("id")).longValue(), row.get("username").toString(),
                row.get("display_name").toString(), row.get("role_code").toString(),
                supplierId == null ? null : supplierId.longValue());
        String token = jwtService.issue(user);
        jdbc.update("update sys_user set last_login_at = current_timestamp where id = ?", user.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessToken", token);
        result.put("tokenType", "Bearer");
        result.put("expiresIn", 28800);
        result.put("user", user);
        return ApiResponse.ok(result, RequestIds.get(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthUser> me(HttpServletRequest request) {
        return ApiResponse.ok(SecuritySupport.currentUser(), RequestIds.get(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(HttpServletRequest request) {
        return ApiResponse.ok(Map.of("message", "已退出；请在客户端删除访问令牌"), RequestIds.get(request));
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
