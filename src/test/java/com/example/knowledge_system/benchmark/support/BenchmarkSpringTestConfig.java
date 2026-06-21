package com.example.knowledge_system.benchmark.support;

import com.example.knowledge_system.benchmark.framework.GoldenKnowledgeSeeder;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.service.Bm25SearchService;
import com.example.knowledge_system.service.VectorService;
import com.github.benmanes.caffeine.cache.Cache;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@TestConfiguration
public class BenchmarkSpringTestConfig {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkSpringTestConfig.class);

    @Bean(name = "bm25SearchService")
    @Primary
    Bm25SearchService bm25SearchService(RestClient restClient) {
        return new Bm25SearchService(restClient) {
            @Override
            public void initIndex() throws IOException {
                try {
                    super.initIndex();
                } catch (Exception ex) {
                    log.warn("Benchmark bootstrap: Elasticsearch unavailable, BM25 index init skipped. {}", ex.getMessage());
                }
            }
        };
    }

    @Bean
    BenchmarkCacheHelper benchmarkCacheHelper(StringRedisTemplate redis,
                                              @Qualifier("ragEmbeddingCache") Cache<String, String> ragEmbeddingCache,
                                              @Qualifier("ragAnswerCache") Cache<String, String> ragAnswerCache) {
        return new BenchmarkCacheHelper(redis, ragEmbeddingCache, ragAnswerCache);
    }

    @Bean
    BenchmarkDataSeeder benchmarkDataSeeder(VectorService vectorService,
                                            JdbcTemplate jdbcTemplate,
                                            DocumentChunkVectorMapper chunkMapper) {
        return new BenchmarkDataSeeder(vectorService, jdbcTemplate, chunkMapper);
    }

    @Bean
    GoldenKnowledgeSeeder goldenKnowledgeSeeder(VectorService vectorService,
                                                  DocumentChunkVectorMapper chunkMapper) {
        return new GoldenKnowledgeSeeder(vectorService, chunkMapper);
    }
}
