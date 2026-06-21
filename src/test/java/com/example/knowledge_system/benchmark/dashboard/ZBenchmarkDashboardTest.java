package com.example.knowledge_system.benchmark.dashboard;

import com.example.knowledge_system.benchmark.framework.BenchmarkDashboardGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import com.example.knowledge_system.benchmark.framework.BenchmarkArtifactWriter;

/**
 * Regenerates final_summary.md when all phase JSON artifacts exist.
 * Skips gracefully when run before other phases complete.
 */
class ZBenchmarkDashboardTest {

    @Test
    void generateFinalSummary() throws Exception {
        Assumptions.assumeTrue(allPhaseArtifactsPresent(),
                "Skipping dashboard — run Retrieval, Cache, E2E, and Reliability benchmarks first");
        BenchmarkDashboardGenerator.generate();
    }

    private boolean allPhaseArtifactsPresent() {
        return Files.exists(BenchmarkArtifactWriter.BENCHMARK_DIR.resolve("retrieval_results.json"))
                && Files.exists(BenchmarkArtifactWriter.BENCHMARK_DIR.resolve("cache_results.json"))
                && Files.exists(BenchmarkArtifactWriter.BENCHMARK_DIR.resolve("e2e_results.json"))
                && Files.exists(BenchmarkArtifactWriter.BENCHMARK_DIR.resolve("reliability_results.json"));
    }
}
