package com.example.knowledge_system.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CaffeineConfig {

    /**
     * RAG embedding 本地一级缓存
     */
    @Bean("ragEmbeddingCache")
    public Cache<String, String> ragEmbeddingCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofHours(2))
                .build();
    }

    /**
     * RAG 最终答案本地一级缓存
     */
    @Bean("ragAnswerCache")
    public Cache<String, String> ragAnswerCache() {
        return Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(15))
                .build();
    }
}
