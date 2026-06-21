package com.example.knowledge_system.service;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class EmbeddingService {

    private static final String EMBEDDING_CACHE_PREFIX = "embedding:";

    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, String> embeddingCaffeineCache;

    public EmbeddingService(
            EmbeddingModel embeddingModel,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("ragEmbeddingCache") Cache<String, String> embeddingCaffeineCache
    ) {
        this.embeddingModel = embeddingModel;
        this.stringRedisTemplate = stringRedisTemplate;
        this.embeddingCaffeineCache = embeddingCaffeineCache;
    }

    public List<Double> embed(String text) {
        String cacheKey = EMBEDDING_CACHE_PREFIX + DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
        String caffeineValue = embeddingCaffeineCache.getIfPresent(cacheKey);
        if (caffeineValue != null && !caffeineValue.isBlank()) {
            log.info("METRIC embeddingCacheHit=1, level=caffeine, key={}", cacheKey);
            return parseVectorString(caffeineValue);
        }

        String cachedVector = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedVector != null && !cachedVector.isBlank()) {
            log.info("METRIC embeddingCacheHit=1, level=redis, key={}", cacheKey);
            embeddingCaffeineCache.put(cacheKey, cachedVector);
            return parseVectorString(cachedVector);
        }

        log.info("METRIC embeddingCacheMiss=1, key={}", cacheKey);

        float[] output = embeddingModel.embed(text);
        List<Double> result = new ArrayList<>(output.length);

        for (float value : output) {
            result.add((double) value);
        }

        String vectorString = toVectorString(result);

        stringRedisTemplate.opsForValue().set(cacheKey, vectorString, Duration.ofDays(1));
        embeddingCaffeineCache.put(cacheKey, vectorString);

        return result;
    }

    public String embedAsVectorString(String text) {
        List<Double> vector = embed(text);
        return toVectorString(vector);
    }

    public String toVectorString(List<Double> vector) {
        return "[" + vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";
    }

    public List<Double> parseVectorString(String vectorString) {
        String trimmed = vectorString.trim();

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        List<Double> result = new ArrayList<>();
        if (trimmed.isBlank()) {
            return result;
        }

        String[] parts = trimmed.split(",");
        for (String part : parts) {
            result.add(Double.parseDouble(part.trim()));
        }
        return result;
    }

    public double cosineSimilarity(List<Double> v1, List<Double> v2) {
        if (v1 == null || v2 == null || v1.isEmpty() || v2.isEmpty()) {
            return -1.0;
        }

        if (v1.size() != v2.size()) {
            return -1.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
            normA += v1.get(i) * v1.get(i);
            normB += v2.get(i) * v2.get(i);
        }

        if (normA == 0 || normB == 0) {
            return -1.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

