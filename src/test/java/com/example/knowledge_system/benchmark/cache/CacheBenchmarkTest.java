package com.example.knowledge_system.benchmark.cache;

import com.example.knowledge_system.benchmark.BenchmarkTestApplication;
import com.example.knowledge_system.benchmark.framework.BenchmarkArtifactWriter;
import com.example.knowledge_system.benchmark.framework.GoldenEvalLoader;
import com.example.knowledge_system.benchmark.framework.GoldenKnowledgeSeeder;
import com.example.knowledge_system.benchmark.support.BenchmarkCacheHelper;
import com.example.knowledge_system.benchmark.support.BenchmarkLogCapture;
import com.example.knowledge_system.benchmark.support.BenchmarkSpringTestConfig;
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
class CacheBenchmarkTest {

    @Autowired
    private VectorService vectorService;
    @Autowired
    private BenchmarkCacheHelper cacheHelper;
    @Autowired
    private GoldenKnowledgeSeeder goldenSeeder;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private List<String> queries;

    @BeforeAll
    void setup() throws Exception {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable: " + ex.getMessage());
        }
        goldenSeeder.ensureGoldenKnowledgeLoaded();
        queries = GoldenEvalLoader.loadEvalCases().stream().map(c -> c.question()).toList();
    }

    @Test
    void runCacheBenchmark() throws Exception {
        Map<String, Object> cold = runPhase("cold", true);
        Map<String, Object> warm = runPhase("warm", false);

        double coldAvg = ((Number) cold.get("avgResponseTimeMs")).doubleValue();
        double warmAvg = ((Number) warm.get("avgResponseTimeMs")).doubleValue();
        double improvement = coldAvg <= 0 ? 0 : ((coldAvg - warmAvg) / coldAvg) * 100.0;

        Map<String, Object> payload = BenchmarkArtifactWriter.resultEnvelope("cache", Map.of(
                "cold", cold,
                "warm", warm,
                "latencyImprovementPct", improvement
        ));
        BenchmarkArtifactWriter.writeJson("cache_results.json", payload);

        StringBuilder md = new StringBuilder(BenchmarkArtifactWriter.header("Cache Benchmark"));
        md.append("## Cold Cache\n\n").append(BenchmarkArtifactWriter.metricsTable(cold));
        md.append("## Warm Cache\n\n").append(BenchmarkArtifactWriter.metricsTable(warm));
        md.append("## Cold vs Warm\n\n");
        md.append("| Metric | Cold | Warm | Improvement |\n|---|---:|---:|---:|\n");
        md.append("| Avg Response Time (ms) | ").append(coldAvg).append(" | ").append(warmAvg)
                .append(" | ").append(String.format("%.1f%%", improvement)).append(" |\n");
        md.append("| L1 Caffeine Hit Rate | ").append(cold.get("l1HitRate")).append(" | ")
                .append(warm.get("l1HitRate")).append(" | - |\n");
        md.append("| L2 Redis Hit Rate | ").append(cold.get("l2HitRate")).append(" | ")
                .append(warm.get("l2HitRate")).append(" | - |\n");
        md.append("| Throughput (QPS) | ").append(cold.get("throughputQps")).append(" | ")
                .append(warm.get("throughputQps")).append(" | - |\n");
        md.append("\n### Cache Layer Legend\n\n");
        md.append("- **L1 (Caffeine)**: `rag:embed` / `embedding` local in-process cache\n");
        md.append("- **L2 (Redis)**: distributed embedding & search result cache\n");
        BenchmarkArtifactWriter.writeMarkdown("cache_benchmark.md", md.toString());
    }

    private Map<String, Object> runPhase(String label, boolean clearEachQuery) throws Exception {
        cacheHelper.clearRedisAndCaffeine();
        List<Long> latencies = new ArrayList<>();
        int l1Hits = 0;
        int l2Hits = 0;
        int misses = 0;
        int rounds = "warm".equals(label) ? 3 : 1;

        long phaseStart = System.currentTimeMillis();
        try (BenchmarkLogCapture capture = new BenchmarkLogCapture()) {
            for (int round = 0; round < rounds; round++) {
                if ("warm".equals(label) && round == 0) {
                    cacheHelper.clearRedisAndCaffeine();
                }
                for (String query : queries) {
                    if (clearEachQuery) {
                        cacheHelper.clearRedisAndCaffeine();
                    }
                    capture.reset();
                    long start = System.currentTimeMillis();
                    vectorService.search(query);
                    latencies.add(System.currentTimeMillis() - start);
                    var metrics = capture.snapshot();
                    l1Hits += metrics.getEmbeddingCacheHits();
                    l2Hits += metrics.getSearchCacheHits();
                    misses += metrics.totalCacheMisses();
                }
            }
        }
        long phaseMs = Math.max(1, System.currentTimeMillis() - phaseStart);
        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", label);
        result.put("queryCount", latencies.size());
        result.put("avgResponseTimeMs", avg(sorted));
        result.put("p95ResponseTimeMs", pct(sorted, 95));
        result.put("p99ResponseTimeMs", pct(sorted, 99));
        result.put("l1CaffeineHits", l1Hits);
        result.put("l2RedisHits", l2Hits);
        result.put("cacheMisses", misses);
        int totalHits = l1Hits + l2Hits;
        result.put("l1HitRate", rate(l1Hits, totalHits + misses));
        result.put("l2HitRate", rate(l2Hits, totalHits + misses));
        result.put("throughputQps", latencies.size() * 1000.0 / phaseMs);
        return result;
    }

    private double avg(long[] sorted) {
        if (sorted.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        return (double) sum / sorted.length;
    }

    private long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private double rate(int hits, int total) {
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
