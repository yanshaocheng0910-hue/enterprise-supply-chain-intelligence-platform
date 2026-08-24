package com.scic.platform.security;

public record AuthUser(Long id, String username, String displayName, String role, Long supplierId) {}

