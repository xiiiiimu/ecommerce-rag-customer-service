package com.example.knowledge_system.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AsyncTaskService {

    private final VectorService vectorService;

    public AsyncTaskService(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    @Async
    public void processUpload(String taskId,
                              MultipartFile[] files,
                              String docType,
                              String status,
                              String startTime,
                              String endTime) {
        try {
            System.out.println("开始异步处理任务：" + taskId);
            vectorService.uploadTxtFiles(files, docType, status, startTime, endTime);
            System.out.println("异步处理完成：" + taskId);
        } catch (Exception e) {
            System.out.println("异步处理失败：" + taskId);
            e.printStackTrace();
        }
    }
}