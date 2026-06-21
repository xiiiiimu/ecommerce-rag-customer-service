package com.example.knowledge_system.reliability.dto;

/**
 * 主业务执行模式：表示本次请求本来应走什么流程。
 */
public enum AnswerMode {
    UNKNOWN,
    ADMIN_TOOL,
    USER_TOOL,
    ORDER,
    ORDER_WITH_RAG,
    RAG
}
