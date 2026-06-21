package com.example.knowledge_system.task;

import com.example.knowledge_system.service.VectorService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentExpireTask {

    private final VectorService vectorService;

    public DocumentExpireTask(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    // 每分钟执行一次，测试方便；后面你可以改成每小时或每天
    @Scheduled(cron = "0 * * * * ?")
    public void expireDocuments() {
        int count = vectorService.expireDocuments();
        if (count > 0) {
            System.out.println("已自动失效文档条数：" + count);
        }
    }
}