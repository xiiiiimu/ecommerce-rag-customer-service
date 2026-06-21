package com.example.knowledge_system.benchmark.framework;

import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.service.VectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GoldenKnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(GoldenKnowledgeSeeder.class);
    private static final String MARKER_PREFIX = "golden_";

    private final VectorService vectorService;
    private final DocumentChunkVectorMapper chunkMapper;

    public GoldenKnowledgeSeeder(VectorService vectorService, DocumentChunkVectorMapper chunkMapper) {
        this.vectorService = vectorService;
        this.chunkMapper = chunkMapper;
    }

    public Map<String, Object> ensureGoldenKnowledgeLoaded() throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        int uploaded = 0;
        int skipped = 0;
        int totalChunks = 0;

        for (Path file : GoldenEvalLoader.listGoldenFiles()) {
            String fileName = file.getFileName().toString();
            String canonicalName = MARKER_PREFIX + fileName;
            int existing = chunkMapper.countChunksByFileName(canonicalName);
            if (existing > 0) {
                skipped++;
                totalChunks += existing;
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String docType = GoldenEvalLoader.docTypes().getOrDefault(fileName, "POLICY");
            var result = vectorService.uploadTextContent(canonicalName, content, docType, "ACTIVE", null, null);
            uploaded++;
            totalChunks += result.getInserted();
            log.info("Golden upload: {} -> {} chunks", canonicalName, result.getInserted());
        }

        stats.put("goldenDirectory", GoldenEvalLoader.resolveGoldenDirectory().toString());
        stats.put("filesUploaded", uploaded);
        stats.put("filesSkipped", skipped);
        stats.put("totalGoldenChunks", totalChunks);
        stats.put("distinctGoldenFiles", chunkMapper.findAllFileNames().stream()
                .filter(name -> name.startsWith(MARKER_PREFIX)).count());
        return stats;
    }

    public static String canonicalFileName(String expectedFile) {
        return MARKER_PREFIX + expectedFile;
    }

    public static boolean matchesExpectedFile(String actualFileName, String expectedFile) {
        if (actualFileName == null || expectedFile == null) {
            return false;
        }
        String canonical = canonicalFileName(expectedFile);
        return actualFileName.equals(canonical)
                || actualFileName.equals(expectedFile)
                || actualFileName.endsWith(expectedFile);
    }
}
