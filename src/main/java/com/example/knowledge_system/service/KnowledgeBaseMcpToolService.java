package com.example.knowledge_system.service;

import com.example.knowledge_system.dto.KafkaUploadMessage;
import com.example.knowledge_system.entity.UploadTask;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KnowledgeBaseMcpToolService {

    private final VectorService vectorService;
    private final UploadTaskService uploadTaskService;
    private final KafkaProducerService kafkaProducerService;

    public KnowledgeBaseMcpToolService(VectorService vectorService,
                                       UploadTaskService uploadTaskService,
                                       KafkaProducerService kafkaProducerService) {
        this.vectorService = vectorService;
        this.uploadTaskService = uploadTaskService;
        this.kafkaProducerService = kafkaProducerService;
    }

    // 1️⃣ 查询知识库文件
    public Object listFiles() {
        return vectorService.listAllFiles();
    }

    // 2️⃣ 删除文件
    public Object deleteFile(String fileName) {
        return vectorService.deleteByFileName(fileName);
    }

    // 3️⃣ 查询版本
    public Object listVersions(String fileName) {
        return vectorService.listVersionsByFileName(fileName);
    }

    // 4️⃣ 查询任务
    public UploadTask queryUploadTask(String taskId) {
        return uploadTaskService.getByTaskId(taskId);
    }

    // 5️⃣ 重试任务
    public String retryUploadTask(String taskId) {

        UploadTask task = uploadTaskService.getByTaskId(taskId);
        if (task == null) {
            return "任务不存在";
        }

        if (!uploadTaskService.canRetry(taskId)) {
            return "当前状态不允许重试: " + task.getStatus();
        }

        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            return "任务文件路径为空，无法重试";
        }

        uploadTaskService.resetToPending(taskId);

        KafkaUploadMessage message = new KafkaUploadMessage();
        message.setTaskId(taskId);

        kafkaProducerService.sendUploadMessage(message);

        return "任务已重新投递: " + taskId;
    }
}