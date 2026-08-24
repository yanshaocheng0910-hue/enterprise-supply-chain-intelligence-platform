package com.scic.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

public final class RequestIds {
    public static final String ATTRIBUTE = "scic.requestId";
    private RequestIds() {}

    public static String get(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing != null) return existing.toString();
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }
}

