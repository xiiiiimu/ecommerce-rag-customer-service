package com.example.knowledge_system.benchmark.support;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

public class BenchmarkCacheHelper {

    private final StringRedisTemplate redis;
    private final Cache<String, String> ragEmbeddingCache;
    private final Cache<String, String> ragAnswerCache;

    public BenchmarkCacheHelper(StringRedisTemplate redis,
                                @Qualifier("ragEmbeddingCache") Cache<String, String> ragEmbeddingCache,
                                @Qualifier("ragAnswerCache") Cache<String, String> ragAnswerCache) {
        this.redis = redis;
        this.ragEmbeddingCache = ragEmbeddingCache;
        this.ragAnswerCache = ragAnswerCache;
    }

    public void clearRedisAndCaffeine() {
        deleteByPattern("rag:embed:*");
        deleteByPattern("embedding:*");
        deleteByPattern("rag:search:*");
        deleteByPattern("rag:answer:*");
        deleteByPattern("rag:hot:counter:*");
        ragEmbeddingCache.invalidateAll();
        ragAnswerCache.invalidateAll();
    }

    public void clearSessionState(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        redis.delete("chat:memory:" + sessionId);
        redis.delete("rag:state:" + sessionId);
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
