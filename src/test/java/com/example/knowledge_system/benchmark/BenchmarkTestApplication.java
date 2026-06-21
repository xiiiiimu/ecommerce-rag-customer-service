package com.example.knowledge_system.benchmark;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.example.knowledge_system.service.Bm25SearchService;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@ComponentScan(
        basePackages = "com.example.knowledge_system",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = Bm25SearchService.class
        )
)
public class BenchmarkTestApplication {
}
