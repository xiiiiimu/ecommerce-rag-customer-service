package com.example.knowledge_system.reliability.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReliabilityLogUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReliabilityLogUtil() {
    }

    public static void logMetric(String name, Object payload) {
        try {
            log.info("METRIC {}={}", name, MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.info("METRIC {}={}", name, String.valueOf(payload));
        }
    }
}
