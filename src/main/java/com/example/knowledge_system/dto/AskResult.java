package com.example.knowledge_system.dto;

import java.util.Collections;
import java.util.List;

public class AskResult {

    private final String answer;
    private final List<DocumentChunkVO> chunks;

    public AskResult(String answer, List<DocumentChunkVO> chunks) {
        this.answer = answer;
        this.chunks = chunks != null ? chunks : Collections.emptyList();
    }

    public static AskResult of(String answer) {
        return new AskResult(answer, Collections.emptyList());
    }

    public static AskResult of(String answer, List<DocumentChunkVO> chunks) {
        return new AskResult(answer, chunks);
    }

    public String getAnswer() {
        return answer;
    }

    public List<DocumentChunkVO> getChunks() {
        return chunks;
    }
}
