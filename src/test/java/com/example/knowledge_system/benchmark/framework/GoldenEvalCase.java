package com.example.knowledge_system.benchmark.framework;

public record GoldenEvalCase(
        String question,
        String expectedFile,
        String expectedKeywords,
        String category
) {
}
