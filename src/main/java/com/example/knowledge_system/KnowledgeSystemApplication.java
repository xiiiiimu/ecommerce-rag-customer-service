package com.example.knowledge_system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
    
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class KnowledgeSystemApplication {

    public static void main(String[] args) {
        System.setProperty("java.io.tmpdir", "E:/temp");
        SpringApplication.run(KnowledgeSystemApplication.class, args);
    }

}
