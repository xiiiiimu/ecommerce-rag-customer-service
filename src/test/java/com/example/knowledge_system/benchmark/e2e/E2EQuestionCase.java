package com.example.knowledge_system.benchmark.e2e;

public record E2EQuestionCase(
        String category,
        String question,
        String expectedIntent,
        String expectedKeywords,
        String expectedFile
) {
}
