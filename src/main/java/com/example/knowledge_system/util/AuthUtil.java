package com.example.knowledge_system.util;

import jakarta.servlet.http.HttpServletRequest;

public class AuthUtil {

    public static String getRole(HttpServletRequest request) {
        String role = request.getHeader("X-ROLE");
        if (role == null || role.isBlank()) {
            return "CUSTOMER";
        }
        return role.trim().toUpperCase();
    }

    public static void requireAdmin(HttpServletRequest request) {
        String role = getRole(request);
        if (!"ADMIN".equals(role)) {
            throw new RuntimeException("权限不足，MCP 工具仅允许管理员调用");
        }
    }
}