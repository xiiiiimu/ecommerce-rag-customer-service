package com.example.knowledge_system.benchmark.support;

import com.example.knowledge_system.benchmark.model.BenchmarkReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class BenchmarkReportWriter {

    private static final Path OUTPUT_DIR = Path.of("target", "benchmark");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private BenchmarkReportWriter() {
    }

    public static void write(BenchmarkReport report) throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        Path jsonPath = OUTPUT_DIR.resolve("benchmark_results.json");
        Path mdPath = OUTPUT_DIR.resolve("benchmark_report.md");
        MAPPER.writeValue(jsonPath.toFile(), report);
        Files.writeString(mdPath, renderMarkdown(report));
    }

    private static String renderMarkdown(BenchmarkReport report) {
        StringBuilder md = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.CHINA);

        md.append("# Ecommerce Customer Service Benchmark Report\n\n");
        md.append("- Generated at: ")
                .append(fmt.format(report.getGeneratedAt().atZone(ZoneId.systemDefault())))
                .append("\n");
        if (report.getEnvironmentNote() != null) {
            md.append("- Note: ").append(report.getEnvironmentNote()).append("\n");
        }
        md.append("\n");

        appendGroupA(md, report.getGroupA());
        appendGroupB(md, report.getGroupB());
        appendGroupC(md, report.getGroupC());
        appendReliability(md, report.getReliability());

        return md.toString();
    }

    private static void appendGroupA(StringBuilder md, BenchmarkReport.GroupMetrics groupA) {
        md.append("## Group A - Cold Start\n\n");
        if (groupA == null) {
            md.append("_Not executed._\n\n");
            return;
        }
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| avgResponseTime | ").append(formatMs(groupA.getAvgResponseTimeMs())).append(" |\n");
        md.append("| avgRetrievalTime | ").append(formatMs(groupA.getAvgRetrievalTimeMs())).append(" |\n");
        md.append("| avgLlmTime | ").append(formatMs(groupA.getAvgLlmTimeMs())).append(" |\n");
        md.append("| cacheHitRate | ").append(formatPct(groupA.getCacheHitRate())).append(" |\n\n");
        appendQueryTable(md, groupA.getQueries());
    }

    private static void appendGroupB(StringBuilder md, BenchmarkReport.GroupBMetrics groupB) {
        md.append("## Group B - Warm Cache\n\n");
        if (groupB == null || groupB.getWarmRun() == null) {
            md.append("_Not executed._\n\n");
            return;
        }
        md.append("| Metric | Warm | Warmup (reference) |\n|---|---|---|\n");
        md.append("| avgResponseTime | ")
                .append(formatMs(groupB.getWarmRun().getAvgResponseTimeMs())).append(" | ")
                .append(formatMs(groupB.getWarmupRun() == null ? 0 : groupB.getWarmupRun().getAvgResponseTimeMs()))
                .append(" |\n");
        md.append("| cacheHitRate | ")
                .append(formatPct(groupB.getWarmRun().getCacheHitRate())).append(" | ")
                .append(formatPct(groupB.getWarmupRun() == null ? 0 : groupB.getWarmupRun().getCacheHitRate()))
                .append(" |\n");
        md.append("| latencyImprovement | ").append(formatPct(groupB.getLatencyImprovementPct() / 100.0)).append(" | - |\n\n");

        md.append("### Cold vs Warm\n\n");
        md.append("| Category | Cold (Group A) | Warm (Group B) | Improvement |\n|---|---|---|---|\n");
        groupB.getWarmResponseByCategory().forEach((category, warmMs) -> {
            double coldMs = groupB.getColdResponseByCategory().getOrDefault(category, 0.0);
            double improvement = coldMs <= 0 ? 0 : ((coldMs - warmMs) / coldMs) * 100.0;
            md.append("| ").append(category).append(" | ")
                    .append(formatMs(coldMs)).append(" | ")
                    .append(formatMs(warmMs)).append(" | ")
                    .append(String.format(Locale.US, "%.1f%%", improvement)).append(" |\n");
        });
        md.append("\n");
    }

    private static void appendGroupC(StringBuilder md, BenchmarkReport.GroupCMetrics groupC) {
        md.append("## Group C - Existing Data\n\n");
        if (groupC == null) {
            md.append("_Not executed._\n\n");
            return;
        }
        md.append("### Seed Stats\n\n");
        md.append("| Item | Count |\n|---|---|\n");
        groupC.getSeedStats().forEach((key, value) ->
                md.append("| ").append(key).append(" | ").append(value).append(" |\n"));
        md.append("\n");

        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Retrieval Recall | ").append(formatPct(groupC.getRetrievalRecall())).append(" (")
                .append(groupC.getRecallHits()).append("/").append(groupC.getRecallSampleSize()).append(") |\n");
        md.append("| Retrieval Latency (avg) | ").append(formatMs(groupC.getAvgRetrievalLatencyMs())).append(" |\n");
        md.append("| LLM Latency (avg) | ").append(formatMs(groupC.getAvgLlmLatencyMs())).append(" |\n");
        md.append("| End-to-End Latency (avg) | ").append(formatMs(groupC.getAvgEndToEndLatencyMs())).append(" |\n\n");
        appendQueryTable(md, groupC.getQueries());
    }

    private static void appendReliability(StringBuilder md, java.util.List<BenchmarkReport.ReliabilityResult> results) {
        md.append("## Reliability Tests\n\n");
        if (results == null || results.isEmpty()) {
            md.append("_Not executed._\n\n");
            return;
        }
        md.append("| Scenario | Degrade | Handoff | Fallback | Passed |\n|---|---|---|---|---|\n");
        for (BenchmarkReport.ReliabilityResult result : results) {
            md.append("| ").append(result.getScenario()).append(" | ")
                    .append(yesNo(result.isDegradationTriggered())).append(" | ")
                    .append(yesNo(result.isHandoffTriggered())).append(" | ")
                    .append(yesNo(result.isFallbackAnswerReturned())).append(" | ")
                    .append(yesNo(result.isPassed())).append(" |\n");
        }
        md.append("\n");
        for (BenchmarkReport.ReliabilityResult result : results) {
            md.append("### ").append(result.getScenario()).append("\n\n");
            md.append("- Answer preview: ").append(nullSafe(result.getAnswerPreview())).append("\n");
            md.append("- Observed metrics: ").append(String.join(", ", result.getObservedMetrics())).append("\n\n");
        }
    }

    private static void appendQueryTable(StringBuilder md, java.util.List<BenchmarkReport.QueryResult> queries) {
        if (queries == null || queries.isEmpty()) {
            return;
        }
        md.append("| Category | Question | Response | Retrieval | LLM | Cache Hit Rate | Chunks | OK |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---|\n");
        for (BenchmarkReport.QueryResult query : queries) {
            int cacheTotal = query.getCacheHits() + query.getCacheMisses();
            double hitRate = cacheTotal == 0 ? 0 : (double) query.getCacheHits() / cacheTotal;
            md.append("| ").append(query.getCategory()).append(" | ")
                    .append(truncate(query.getQuestion(), 40)).append(" | ")
                    .append(query.getResponseTimeMs()).append(" | ")
                    .append(query.getRetrievalTimeMs()).append(" | ")
                    .append(query.getLlmTimeMs()).append(" | ")
                    .append(formatPct(hitRate)).append(" | ")
                    .append(query.getChunkCount()).append(" | ")
                    .append(yesNo(query.isSuccess())).append(" |\n");
        }
        md.append("\n");
    }

    private static String formatMs(double value) {
        return String.format(Locale.US, "%.1f ms", value);
    }

    private static String formatPct(double value) {
        return String.format(Locale.US, "%.1f%%", value * 100.0);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }
}
