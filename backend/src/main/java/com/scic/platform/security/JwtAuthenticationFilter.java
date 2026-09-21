package com.scic.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String value = request.getHeader("Authorization");
        if (value != null && value.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthUser tokenUser = jwtService.verify(value.substring(7));
                AuthUser user = loadActiveUser(tokenUser);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        user, value.substring(7), List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private AuthUser loadActiveUser(AuthUser tokenUser) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,username,display_name,role_code,supplier_id,enabled from sys_user where id=? and username=?",
                tokenUser.id(), tokenUser.username());
        if (rows.size() != 1 || !Boolean.TRUE.equals(rows.get(0).get("enabled"))) {
            throw new IllegalArgumentException("Token account is missing or disabled");
        }
        Map<String, Object> row = rows.get(0);
        Number supplierId = (Number) row.get("supplier_id");
        return new AuthUser(((Number) row.get("id")).longValue(), row.get("username").toString(),
                row.get("display_name").toString(), row.get("role_code").toString(),
                supplierId == null ? null : supplierId.longValue());
    }
}
