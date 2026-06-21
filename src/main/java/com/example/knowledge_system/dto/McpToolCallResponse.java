package com.example.knowledge_system.dto;

public class McpToolCallResponse {
    private Object content;
    private boolean error;

    public McpToolCallResponse() {
    }

    public McpToolCallResponse(Object content, boolean error) {
        this.content = content;
        this.error = error;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }
}

