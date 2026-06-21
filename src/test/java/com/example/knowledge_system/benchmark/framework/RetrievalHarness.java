package com.example.knowledge_system.benchmark.framework;

import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.service.Bm25SearchService;
import com.example.knowledge_system.service.EmbeddingService;
import com.example.knowledge_system.service.VectorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RetrievalHarness {

    private static final double VECTOR_SCORE_THRESHOLD = 0.30;
    private static final double BM25_SCORE_THRESHOLD = 0.1;
    private static final int RRF_K = 60;

    private final VectorService vectorService;
    private final Bm25SearchService bm25SearchService;
    private final EmbeddingService embeddingService;
    private final DocumentChunkVectorMapper mapper;

    public RetrievalHarness(VectorService vectorService,
                            Bm25SearchService bm25SearchService,
                            EmbeddingService embeddingService,
                            DocumentChunkVectorMapper mapper) {
        this.vectorService = vectorService;
        this.bm25SearchService = bm25SearchService;
        this.embeddingService = embeddingService;
        this.mapper = mapper;
    }

    public List<DocumentChunkVO> search(RetrievalStrategy strategy, String query) {
        String normalized = vectorService.normalizeQuestionForSearch(query);
        return switch (strategy) {
            case BM25 -> bm25Only(normalized, 30);
            case PGVECTOR -> vectorOnly(normalized, 30);
            case HYBRID_RRF -> hybridRrf(normalized, 10);
            case HYBRID_RERANK -> vectorService.search(normalized);
        };
    }

    private List<DocumentChunkVO> bm25Only(String normalized, int limit) {
        List<DocumentChunkVO> result = bm25SearchService.search(normalized, limit);
        if (result == null) {
            return List.of();
        }
        return result.stream()
                .filter(item -> item.getScore() == null || item.getScore() >= BM25_SCORE_THRESHOLD)
                .limit(limit)
                .toList();
    }

    private List<DocumentChunkVO> vectorOnly(String normalized, int limit) {
        String embedding = embeddingService.embedAsVectorString(normalized);
        List<DocumentChunkVO> result = mapper.searchSimilar(embedding);
        if (result == null) {
            return List.of();
        }
        return result.stream()
                .filter(item -> item.getScore() == null || item.getScore() >= VECTOR_SCORE_THRESHOLD)
                .limit(limit)
                .toList();
    }

    private List<DocumentChunkVO> hybridRrf(String normalized, int finalTopK) {
        List<DocumentChunkVO> vectorResult = vectorOnly(normalized, 30);
        List<DocumentChunkVO> bm25Result = bm25Only(normalized, 30);
        if (vectorResult.isEmpty() && bm25Result.isEmpty()) {
            return List.of();
        }
        return mergeByRrf(vectorResult, bm25Result, finalTopK);
    }

    private List<DocumentChunkVO> mergeByRrf(List<DocumentChunkVO> vectorList,
                                             List<DocumentChunkVO> bm25List,
                                             int finalTopK) {
        Map<String, DocumentChunkVO> itemMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new LinkedHashMap<>();
        addRrfScores(vectorList, itemMap, scoreMap);
        addRrfScores(bm25List, itemMap, scoreMap);
        return scoreMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(finalTopK)
                .map(entry -> itemMap.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    private void addRrfScores(List<DocumentChunkVO> list,
                              Map<String, DocumentChunkVO> itemMap,
                              Map<String, Double> scoreMap) {
        if (list == null || list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            DocumentChunkVO item = list.get(i);
            if (item == null) {
                continue;
            }
            String key = item.getFileName() + "_" + item.getChunkIndex();
            itemMap.putIfAbsent(key, item);
            double score = 1.0 / (RRF_K + i + 1);
            scoreMap.put(key, scoreMap.getOrDefault(key, 0.0) + score);
        }
    }
}
