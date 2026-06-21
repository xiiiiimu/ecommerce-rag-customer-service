package com.example.knowledge_system.benchmark.framework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BenchmarkArtifactWriter {

    public static final Path BENCHMARK_DIR = Path.of("target", "benchmark");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private BenchmarkArtifactWriter() {
    }

    public static void writeJson(String fileName, Object payload) throws IOException {
        Files.createDirectories(BENCHMARK_DIR);
        MAPPER.writeValue(BENCHMARK_DIR.resolve(fileName).toFile(), payload);
    }

    public static void writeMarkdown(String fileName, String content) throws IOException {
        Files.createDirectories(BENCHMARK_DIR);
        Files.writeString(BENCHMARK_DIR.resolve(fileName), content);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJson(String fileName) throws IOException {
        Path path = BENCHMARK_DIR.resolve(fileName);
        if (!Files.exists(path)) {
            return Map.of();
        }
        return MAPPER.readValue(path.toFile(), Map.class);
    }

    public static String header(String title) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(Locale.CHINA);
        return "# " + title + "\n\n- Generated at: "
                + fmt.format(Instant.now().atZone(ZoneId.systemDefault())) + "\n\n";
    }

    public static String metricsTable(Map<String, Object> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("| Metric | Value |\n|---|---|\n");
        metrics.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("\n");
        return sb.toString();
    }

    public static Map<String, Object> resultEnvelope(String phase, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("phase", phase);
        envelope.put("generatedAt", Instant.now().toString());
        envelope.putAll(payload);
        return envelope;
    }
}
