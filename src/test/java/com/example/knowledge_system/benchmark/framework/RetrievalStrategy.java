package com.example.knowledge_system.benchmark.framework;

public enum RetrievalStrategy {
    BM25("A. BM25 (Elasticsearch)"),
    PGVECTOR("B. PGVector"),
    HYBRID_RRF("C. BM25 + PGVector Hybrid (RRF)"),
    HYBRID_RERANK("D. BM25 + PGVector + Rerank");

    private final String label;

    RetrievalStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
