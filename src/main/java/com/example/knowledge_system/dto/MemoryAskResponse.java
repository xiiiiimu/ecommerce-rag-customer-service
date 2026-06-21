package com.example.knowledge_system.dto;

public class MemoryAskResponse {
    private String answer;
    private String sessionId;

    public MemoryAskResponse(String answer, String sessionId) {
        this.answer = answer;
        this.sessionId = sessionId;
    }

    public String getAnswer() {
        return answer;
    }

    public String getSessionId() {
        return sessionId;
    }
}
