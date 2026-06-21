package com.example.knowledge_system.reliability.dto;

/**
 * 证据状态：仅用于 RAG / ORDER_WITH_RAG 流程。
 */
public enum EvidenceStatus {
    NONE,
    INSUFFICIENT,
    CONFLICT,
    PARTIAL,
    HIGH_CONFIDENCE
}
