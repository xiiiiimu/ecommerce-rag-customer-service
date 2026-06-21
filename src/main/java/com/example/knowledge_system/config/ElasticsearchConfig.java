package com.example.knowledge_system.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ElasticsearchConfig {

    @Bean
    public RestClient elasticsearchRestClient(
            @Value("${elasticsearch.url}") String elasticsearchUrl
    ) {
        URI uri = URI.create(elasticsearchUrl);
        return RestClient.builder(
                new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())
        ).build();
    }
}