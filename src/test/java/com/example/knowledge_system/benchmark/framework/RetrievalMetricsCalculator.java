package com.example.knowledge_system.benchmark.framework;

import com.example.knowledge_system.dto.DocumentChunkVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RetrievalMetricsCalculator {

    private RetrievalMetricsCalculator() {
    }

    public static Map<String, Object> aggregate(List<QueryEvalResult> results) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        int n = results.size();
        if (n == 0) {
            return metrics;
        }
        metrics.put("queryCount", n);
        metrics.put("recallAt1", avg(results, QueryEvalResult::recallAt1));
        metrics.put("recallAt3", avg(results, QueryEvalResult::recallAt3));
        metrics.put("recallAt5", avg(results, QueryEvalResult::recallAt5));
        metrics.put("recallAt10", avg(results, QueryEvalResult::recallAt10));
        metrics.put("mrr", avg(results, QueryEvalResult::mrr));
        metrics.put("ndcgAt10", avg(results, QueryEvalResult::ndcgAt10));
        metrics.put("precisionAt5", avg(results, QueryEvalResult::precisionAt5));
        metrics.put("avgLatencyMs", avgLong(results, QueryEvalResult::latencyMs));
        metrics.put("p95LatencyMs", percentile(results.stream().mapToLong(QueryEvalResult::latencyMs).sorted().toArray(), 95));
        metrics.put("p99LatencyMs", percentile(results.stream().mapToLong(QueryEvalResult::latencyMs).sorted().toArray(), 99));
        return metrics;
    }

    public static QueryEvalResult evaluate(GoldenEvalCase evalCase, List<DocumentChunkVO> ranked, long latencyMs) {
        String expected = evalCase.expectedFile();
        int firstRelevantRank = 0;
        List<Double> gains = new ArrayList<>();
        int relevantInTop5 = 0;

        for (int i = 0; i < ranked.size(); i++) {
            boolean relevant = GoldenKnowledgeSeeder.matchesExpectedFile(ranked.get(i).getFileName(), expected);
            if (relevant && firstRelevantRank == 0) {
                firstRelevantRank = i + 1;
            }
            gains.add(relevant ? 1.0 : 0.0);
            if (i < 5 && relevant) {
                relevantInTop5++;
            }
        }

        double mrr = firstRelevantRank > 0 ? 1.0 / firstRelevantRank : 0.0;
        double ndcg = ndcgAtK(gains, 10);
        double precision5 = ranked.isEmpty() ? 0.0 : relevantInTop5 / 5.0;

        return new QueryEvalResult(
                evalCase.question(),
                expected,
                recallAt(ranked, expected, 1),
                recallAt(ranked, expected, 3),
                recallAt(ranked, expected, 5),
                recallAt(ranked, expected, 10),
                mrr,
                ndcg,
                precision5,
                latencyMs
        );
    }

    private static double recallAt(List<DocumentChunkVO> ranked, String expected, int k) {
        int limit = Math.min(k, ranked.size());
        for (int i = 0; i < limit; i++) {
            if (GoldenKnowledgeSeeder.matchesExpectedFile(ranked.get(i).getFileName(), expected)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private static double ndcgAtK(List<Double> gains, int k) {
        if (gains.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, gains.size());
        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            dcg += gains.get(i) / (Math.log(i + 2) / Math.log(2));
        }
        List<Double> ideal = new ArrayList<>(gains);
        ideal.sort((a, b) -> Double.compare(b, a));
        double idcg = 0.0;
        for (int i = 0; i < limit; i++) {
            idcg += ideal.get(i) / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    private static double avg(List<QueryEvalResult> results, java.util.function.ToDoubleFunction<QueryEvalResult> fn) {
        return results.stream().mapToDouble(fn).average().orElse(0.0);
    }

    private static double avgLong(List<QueryEvalResult> results, java.util.function.ToLongFunction<QueryEvalResult> fn) {
        return results.stream().mapToLong(fn).average().orElse(0.0);
    }

    private static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx];
    }

    public record QueryEvalResult(
            String question,
            String expectedFile,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrr,
            double ndcgAt10,
            double precisionAt5,
            long latencyMs
    ) {
    }
}
