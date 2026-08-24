package com.scic.platform.system;

import com.scic.platform.security.AuthUser;
import com.scic.platform.security.SecuritySupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String requestId, String action, String targetType, Long targetId,
                    String beforeState, String afterState, String detail) {
        AuthUser user = SecuritySupport.currentUser();
        jdbc.update("insert into operation_log(request_id, operator_id, operator_name, role_code, action_code, target_type, target_id, before_state, after_state, detail_text) values(?,?,?,?,?,?,?,?,?,?)",
                requestId, user.id(), user.displayName(), user.role(), action, targetType, targetId, beforeState, afterState, detail);
    }
}

