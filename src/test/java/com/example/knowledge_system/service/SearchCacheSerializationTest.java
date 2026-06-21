package com.example.knowledge_system.service;

import com.example.knowledge_system.dto.DocumentChunkVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the same Jackson config used by {@link VectorService} search-result cache.
 */
class SearchCacheSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void documentChunkVoWithLocalDateTime_roundTrips() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 6, 1, 10, 30, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 11, 23, 59, 59);
        LocalDateTime updated = LocalDateTime.of(2026, 6, 12, 14, 0, 0);

        DocumentChunkVO chunk = new DocumentChunkVO();
        chunk.setId(1L);
        chunk.setContent("双11满减规则说明");
        chunk.setFileName("golden_double11_rules.txt");
        chunk.setChunkIndex(0);
        chunk.setDocType("POLICY");
        chunk.setScore(0.92);
        chunk.setStartTime(start);
        chunk.setEndTime(end);
        chunk.setUpdatedAt(updated);

        String json = objectMapper.writeValueAsString(List.of(chunk));
        assertFalse(json.contains("\"startTime\":["), "dates must not be written as timestamps");

        List<DocumentChunkVO> restored = objectMapper.readValue(
                json,
                new TypeReference<List<DocumentChunkVO>>() {}
        );

        assertEquals(1, restored.size());
        DocumentChunkVO roundTripped = restored.get(0);
        assertEquals(chunk.getId(), roundTripped.getId());
        assertEquals(chunk.getContent(), roundTripped.getContent());
        assertEquals(chunk.getFileName(), roundTripped.getFileName());
        assertNotNull(roundTripped.getStartTime());
        assertEquals(start, roundTripped.getStartTime());
        assertEquals(end, roundTripped.getEndTime());
        assertEquals(updated, roundTripped.getUpdatedAt());
    }
}
