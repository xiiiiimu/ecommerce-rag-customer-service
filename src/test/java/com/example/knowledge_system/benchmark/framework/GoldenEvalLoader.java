package com.example.knowledge_system.benchmark.framework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GoldenEvalLoader {

    private static final Path DESKTOP_GOLDEN = Path.of("C:", "Users", "西木", "Desktop", "新建文件夹 (2)");
    private static final Map<String, String> DOC_TYPES = Map.ofEntries(
            Map.entry("faq.txt", "FAQ"),
            Map.entry("coupon_rules.txt", "POLICY"),
            Map.entry("double11_rules.txt", "POLICY"),
            Map.entry("logistics_rules.txt", "POLICY"),
            Map.entry("refund_rules.txt", "POLICY"),
            Map.entry("return_rules.txt", "POLICY"),
            Map.entry("shipping_rules.txt", "POLICY"),
            Map.entry("policy.txt", "POLICY"),
            Map.entry("long.txt", "LONG_TEXT"),
            Map.entry("mixed.txt", "LONG_TEXT"),
            Map.entry("table.txt", "TABLE")
    );

    private GoldenEvalLoader() {
    }

    public static List<GoldenEvalCase> loadEvalCases() throws IOException {
        List<GoldenEvalCase> cases = new ArrayList<>();
        try (InputStream in = GoldenEvalLoader.class.getResourceAsStream("/benchmark/golden_eval_questions.csv")) {
            if (in == null) {
                throw new IOException("golden_eval_questions.csv not found in classpath");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.readLine();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split(",", 4);
                    if (parts.length < 3) {
                        continue;
                    }
                    String category = parts.length >= 4 ? parts[3].trim() : "general";
                    cases.add(new GoldenEvalCase(parts[0].trim(), parts[1].trim(), parts[2].trim(), category));
                }
            }
        }
        return cases;
    }

    public static Map<String, String> docTypes() {
        return DOC_TYPES;
    }

    public static Path resolveGoldenDirectory() {
        if (Files.isDirectory(DESKTOP_GOLDEN)) {
            return DESKTOP_GOLDEN;
        }
        return Path.of("src/test/resources/benchmark/golden");
    }

    public static List<Path> listGoldenFiles() throws IOException {
        Path dir = resolveGoldenDirectory();
        if (!Files.isDirectory(dir)) {
            throw new IOException("Golden directory not found: " + dir);
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
    }
}
