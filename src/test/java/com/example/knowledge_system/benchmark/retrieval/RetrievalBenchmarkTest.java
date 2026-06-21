package com.example.knowledge_system.benchmark.retrieval;

import com.example.knowledge_system.benchmark.BenchmarkTestApplication;
import com.example.knowledge_system.benchmark.framework.BenchmarkArtifactWriter;
import com.example.knowledge_system.benchmark.framework.BenchmarkDashboardGenerator;
import com.example.knowledge_system.benchmark.framework.GoldenEvalCase;
import com.example.knowledge_system.benchmark.framework.GoldenEvalLoader;
import com.example.knowledge_system.benchmark.framework.GoldenKnowledgeSeeder;
import com.example.knowledge_system.benchmark.framework.RetrievalHarness;
import com.example.knowledge_system.benchmark.framework.RetrievalMetricsCalculator;
import com.example.knowledge_system.benchmark.framework.RetrievalStrategy;
import com.example.knowledge_system.benchmark.support.BenchmarkCacheHelper;
import com.example.knowledge_system.benchmark.support.BenchmarkSpringTestConfig;
import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.service.Bm25SearchService;
import com.example.knowledge_system.service.EmbeddingService;
import com.example.knowledge_system.service.VectorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = BenchmarkTestApplication.class)
@Import(BenchmarkSpringTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalBenchmarkTest {

    @Autowired
    private VectorService vectorService;
    @Autowired
    private Bm25SearchService bm25SearchService;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private DocumentChunkVectorMapper mapper;
    @Autowired
    private BenchmarkCacheHelper cacheHelper;
    @Autowired
    private GoldenKnowledgeSeeder goldenSeeder;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private List<GoldenEvalCase> evalCases;
    private Map<String, Object> seedStats;

    @BeforeAll
    void setup() throws Exception {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable: " + ex.getMessage());
        }
        seedStats = goldenSeeder.ensureGoldenKnowledgeLoaded();
        evalCases = GoldenEvalLoader.loadEvalCases();
    }

    @Test
    void runRetrievalBenchmark() throws Exception {
        RetrievalHarness harness = new RetrievalHarness(vectorService, bm25SearchService, embeddingService, mapper);
        Map<String, Object> allStrategies = new LinkedHashMap<>();
        Map<String, Object> strategyMetrics = new LinkedHashMap<>();

        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            cacheHelper.clearRedisAndCaffeine();
            List<RetrievalMetricsCalculator.QueryEvalResult> results = new ArrayList<>();
            for (GoldenEvalCase evalCase : evalCases) {
                if (strategy != RetrievalStrategy.HYBRID_RERANK) {
                    cacheHelper.clearRedisAndCaffeine();
                }
                long start = System.currentTimeMillis();
                List<DocumentChunkVO> ranked = harness.search(strategy, evalCase.question());
                long latency = System.currentTimeMillis() - start;
                results.add(RetrievalMetricsCalculator.evaluate(evalCase, ranked, latency));
            }
            Map<String, Object> metrics = RetrievalMetricsCalculator.aggregate(results);
            metrics.put("strategy", strategy.name());
            metrics.put("label", strategy.label());
            strategyMetrics.put(strategy.name(), metrics);
            allStrategies.put(strategy.name(), Map.of(
                    "metrics", metrics,
                    "samples", results.stream().limit(5).toList()
            ));
        }

        Map<String, Object> payload = BenchmarkArtifactWriter.resultEnvelope("retrieval", Map.of(
                "seedStats", seedStats,
                "evalCaseCount", evalCases.size(),
                "strategies", strategyMetrics
        ));
        BenchmarkArtifactWriter.writeJson("retrieval_results.json", payload);

        StringBuilder md = new StringBuilder(BenchmarkArtifactWriter.header("Retrieval Benchmark"));
        md.append("## Golden Dataset\n\n");
        md.append(BenchmarkArtifactWriter.metricsTable(seedStats));
        md.append("## Eval Cases: ").append(evalCases.size()).append("\n\n");
        md.append("## Strategy Comparison\n\n");
        md.append("| Strategy | Recall@1 | Recall@5 | Recall@10 | MRR | NDCG@10 | P@5 | Avg Latency(ms) | P95(ms) |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (RetrievalStrategy strategy : RetrievalStrategy.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) strategyMetrics.get(strategy.name());
            md.append("| ").append(strategy.label()).append(" | ")
                    .append(fmt(m.get("recallAt1"))).append(" | ")
                    .append(fmt(m.get("recallAt5"))).append(" | ")
                    .append(fmt(m.get("recallAt10"))).append(" | ")
                    .append(fmt(m.get("mrr"))).append(" | ")
                    .append(fmt(m.get("ndcgAt10"))).append(" | ")
                    .append(fmt(m.get("precisionAt5"))).append(" | ")
                    .append(fmt(m.get("avgLatencyMs"))).append(" | ")
                    .append(m.get("p95LatencyMs")).append(" |\n");
        }
        md.append("\n## Winner Analysis\n\n");
        md.append(buildWinnerNotes(strategyMetrics));
        BenchmarkArtifactWriter.writeMarkdown("retrieval_benchmark.md", md.toString());
        tryGenerateFinalSummary();
    }

    private void tryGenerateFinalSummary() {
        try {
            BenchmarkDashboardGenerator.generate();
        } catch (IllegalStateException ignored) {
            // Other phase artifacts missing when retrieval runs alone
        } catch (Exception ex) {
            throw new RuntimeException("Failed to generate final benchmark summary", ex);
        }
    }

    private String fmt(Object value) {
        if (value instanceof Number number) {
            return String.format("%.3f", number.doubleValue());
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private String buildWinnerNotes(Map<String, Object> strategyMetrics) {
        String bestRecall = bestBy(strategyMetrics, "recallAt5");
        String bestMrr = bestBy(strategyMetrics, "mrr");
        String bestLatency = lowestBy(strategyMetrics, "avgLatencyMs");
        return "- Best Recall@5: **" + bestRecall + "**\n"
                + "- Best MRR: **" + bestMrr + "**\n"
                + "- Lowest Latency: **" + bestLatency + "**\n";
    }

    @SuppressWarnings("unchecked")
    private String bestBy(Map<String, Object> strategyMetrics, String key) {
        String best = "";
        double bestVal = -1;
        for (var entry : strategyMetrics.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) entry.getValue();
            double val = ((Number) m.get(key)).doubleValue();
            if (val > bestVal) {
                bestVal = val;
                best = entry.getKey();
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private String lowestBy(Map<String, Object> strategyMetrics, String key) {
        String best = "";
        double bestVal = Double.MAX_VALUE;
        for (var entry : strategyMetrics.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) entry.getValue();
            double val = ((Number) m.get(key)).doubleValue();
            if (val < bestVal) {
                bestVal = val;
                best = entry.getKey();
            }
        }
        return best;
    }
}
