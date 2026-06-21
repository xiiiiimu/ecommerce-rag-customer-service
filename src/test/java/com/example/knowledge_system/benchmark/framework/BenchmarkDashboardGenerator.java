package com.example.knowledge_system.benchmark.framework;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")
public final class BenchmarkDashboardGenerator {

    private static final List<String> REQUIRED_JSON = List.of(
            "retrieval_results.json",
            "cache_results.json",
            "e2e_results.json",
            "reliability_results.json"
    );

    private BenchmarkDashboardGenerator() {
    }

    public static void generate() throws Exception {
        requirePhaseArtifacts();

        Map<String, Object> retrieval = BenchmarkArtifactWriter.readJson("retrieval_results.json");
        Map<String, Object> cache = BenchmarkArtifactWriter.readJson("cache_results.json");
        Map<String, Object> e2e = BenchmarkArtifactWriter.readJson("e2e_results.json");
        Map<String, Object> reliability = BenchmarkArtifactWriter.readJson("reliability_results.json");

        Map<String, Object> retrievalStrategies = (Map<String, Object>) retrieval.getOrDefault("strategies", Map.of());
        Map<String, Object> cold = (Map<String, Object>) cache.getOrDefault("cold", Map.of());
        Map<String, Object> warm = (Map<String, Object>) cache.getOrDefault("warm", Map.of());
        Map<String, Object> e2eSummary = (Map<String, Object>) e2e.getOrDefault("summary", Map.of());

        String bestRecall = bestStrategy(retrievalStrategies, "recallAt5");
        String slowestRetrieval = highestStrategy(retrievalStrategies, "avgLatencyMs");
        String hybridRecall = metric(retrievalStrategies, "HYBRID_RERANK", "recallAt5");
        String bm25Recall = metric(retrievalStrategies, "BM25", "recallAt5");
        String vectorRecall = metric(retrievalStrategies, "PGVECTOR", "recallAt5");
        double hybridRerankLatency = num(metricMap(retrievalStrategies, "HYBRID_RERANK").get("avgLatencyMs"));
        double bm25Latency = num(metricMap(retrievalStrategies, "BM25").get("avgLatencyMs"));

        double coldAvg = num(cold.get("avgResponseTimeMs"));
        double warmAvg = num(warm.get("avgResponseTimeMs"));
        double cacheImprovement = num(cache.get("latencyImprovementPct"));
        double e2eAvg = num(e2eSummary.get("avgEndToEndLatencyMs"));

        StringBuilder md = new StringBuilder(BenchmarkArtifactWriter.header("Benchmark Framework — Final Summary"));
        md.append("## Executive Answers\n\n");
        md.append("1. **BM25 与 Vector 谁更好？**  ");
        md.append("BM25 Recall@5=").append(bm25Recall).append("，PGVector Recall@5=").append(vectorRecall);
        md.append(" → 当前 Golden Dataset 上 **")
                .append(parseDouble(bm25Recall) >= parseDouble(vectorRecall) ? "BM25" : "PGVector")
                .append("** 更优。\n\n");
        md.append("2. **Hybrid 是否值得？**  Hybrid+Rerank Recall@5=").append(hybridRecall);
        md.append("，相对单通道提升 ")
                .append(String.format("%.1f%%",
                        (parseDouble(hybridRecall) - Math.max(parseDouble(bm25Recall), parseDouble(vectorRecall))) * 100))
                .append("；代价是检索延迟从 ").append(String.format("%.0f", bm25Latency))
                .append("ms 升至 ").append(String.format("%.0f", hybridRerankLatency)).append("ms。\n\n");
        md.append("3. **Redis+Caffeine 提升了多少？**  Cold ").append(String.format("%.0f", coldAvg))
                .append("ms → Warm ").append(String.format("%.0f", warmAvg))
                .append("ms，改善 **").append(String.format("%.1f%%", cacheImprovement)).append("**");
        md.append("（L1 Caffeine 热命中率 ").append(formatPct(warm.get("l1HitRate")))
                .append("，L2 Redis 热命中率 ").append(formatPct(warm.get("l2HitRate"))).append("）。\n\n");
        md.append("4. **冷启动 vs 热启动差距？**  Avg Cold=").append(String.format("%.0f", coldAvg))
                .append("ms → Warm=").append(String.format("%.0f", warmAvg)).append("ms；P95 Cold=")
                .append(cold.get("p95ResponseTimeMs")).append("ms，Warm=").append(warm.get("p95ResponseTimeMs")).append("ms。\n\n");
        md.append("5. **系统瓶颈组件？**  ");
        md.append("E2E Avg Latency=").append(String.format("%.0f", e2eAvg))
                .append("ms；检索最慢策略 **").append(slowestRetrieval).append("**。\n\n");
        md.append("6. **最影响用户体验的组件？**  ");
        md.append("Fallback Rate=").append(formatPct(e2eSummary.get("fallbackTriggerRate")))
                .append("，Answer Accuracy=").append(formatPct(e2eSummary.get("answerAccuracy"))).append("。\n\n");
        md.append("7. **后续 ROI 最高优化方向？**  ");
        md.append(recommendRoi(bestRecall, cacheImprovement, e2eSummary, reliability)).append("\n\n");

        md.append("## Phase Reports\n\n");
        md.append("| Phase | Report | JSON |\n|---|---|---|\n");
        md.append("| Retrieval | retrieval_benchmark.md | retrieval_results.json |\n");
        md.append("| Cache | cache_benchmark.md | cache_results.json |\n");
        md.append("| E2E | e2e_benchmark.md | e2e_results.json |\n");
        md.append("| Reliability | reliability_benchmark.md | reliability_results.json |\n\n");

        md.append("## Retrieval Leaderboard\n\n");
        md.append("| Strategy | Recall@5 | MRR | Avg Latency(ms) |\n|---|---:|---:|---:|\n");
        for (String key : List.of("BM25", "PGVECTOR", "HYBRID_RRF", "HYBRID_RERANK")) {
            Map<String, Object> m = (Map<String, Object>) retrievalStrategies.get(key);
            if (m == null) {
                continue;
            }
            md.append("| ").append(key).append(" | ")
                    .append(m.get("recallAt5")).append(" | ")
                    .append(m.get("mrr")).append(" | ")
                    .append(m.get("avgLatencyMs")).append(" |\n");
        }

        md.append("\n## Reliability\n\n");
        md.append("- Fallback Success Rate: **").append(reliability.get("fallbackSuccessRate")).append("**\n");
        md.append("- Passed Scenarios: **").append(reliability.get("passedCount")).append("/")
                .append(reliability.get("scenarioCount")).append("**\n");

        Map<String, Object> summaryPayload = new LinkedHashMap<>();
        summaryPayload.put("generatedAt", java.time.Instant.now().toString());
        summaryPayload.put("retrievalBestRecallStrategy", bestRecall);
        summaryPayload.put("cacheImprovementPct", cacheImprovement);
        summaryPayload.put("e2eSummary", e2eSummary);
        summaryPayload.put("reliability", reliability);
        BenchmarkArtifactWriter.writeJson("final_summary.json", summaryPayload);
        BenchmarkArtifactWriter.writeMarkdown("final_summary.md", md.toString());
    }

    private static void requirePhaseArtifacts() {
        List<String> missing = REQUIRED_JSON.stream()
                .filter(name -> !Files.exists(BenchmarkArtifactWriter.BENCHMARK_DIR.resolve(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing benchmark artifacts: " + missing + ". Run phase tests first (Retrieval, Cache, E2E, Reliability).");
        }
    }

    private static String bestStrategy(Map<String, Object> strategies, String metric) {
        String best = "N/A";
        double bestVal = -1;
        for (var entry : strategies.entrySet()) {
            double val = num(metricMap(strategies, entry.getKey()).get(metric));
            if (val > bestVal) {
                bestVal = val;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static String highestStrategy(Map<String, Object> strategies, String metric) {
        String best = "N/A";
        double bestVal = -1;
        for (var entry : strategies.entrySet()) {
            double val = num(metricMap(strategies, entry.getKey()).get(metric));
            if (val > bestVal) {
                bestVal = val;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static Map<String, Object> metricMap(Map<String, Object> strategies, String key) {
        Object value = strategies.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String metric(Map<String, Object> strategies, String key, String metric) {
        Map<String, Object> m = metricMap(strategies, key);
        return m.isEmpty() ? "N/A" : String.valueOf(m.get(metric));
    }

    private static double num(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private static String formatPct(Object rate) {
        if (rate instanceof Number n) {
            return String.format("%.1f%%", n.doubleValue() * 100);
        }
        return "N/A";
    }

    private static String recommendRoi(String bestRecall,
                                       double cacheImprovement,
                                       Map<String, Object> e2eSummary,
                                       Map<String, Object> reliability) {
        double fallback = num(e2eSummary.get("fallbackTriggerRate"));
        double reliabilityPass = num(reliability.get("fallbackSuccessRate"));
        if (reliabilityPass < 0.8) {
            return "优先增强 **Redis/会话状态容错** 与故障降级（Reliability 通过率偏低）。";
        }
        if (fallback > 0.2) {
            return "优先优化 **LLM 稳定性/兜底策略**（Fallback 偏高）。";
        }
        if (cacheImprovement < 30) {
            return "优先优化 **缓存键设计与预热策略**（缓存收益偏低）。";
        }
        return "优先固化 **" + bestRecall + "** 检索策略并扩展 Golden Dataset 覆盖。";
    }
}
