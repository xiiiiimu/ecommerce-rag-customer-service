package com.example.knowledge_system.service;

import com.example.knowledge_system.dto.KafkaUploadMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import com.example.knowledge_system.entity.UploadTask;
import java.util.List;

@Service
public class KafkaConsumerService {

    private final VectorService vectorService;
    private final UploadTaskService uploadTaskService;
    private final KafkaProducerService kafkaProducerService;
    private final FileParseService fileParseService;

    private static final int MAX_RETRY = 3;
    public KafkaConsumerService(VectorService vectorService,
                                UploadTaskService uploadTaskService,
                                KafkaProducerService kafkaProducerService,
                                FileParseService fileParseService) {
        this.vectorService = vectorService;
        this.uploadTaskService = uploadTaskService;
        this.kafkaProducerService = kafkaProducerService;
        this.fileParseService = fileParseService;
    }

    @KafkaListener(topics = "knowledge-upload-topic", groupId = "knowledge-system-group")
    public void consumeUploadMessage(KafkaUploadMessage message) {
        String taskId = message.getTaskId();

        System.out.println("=== 进入 consumeUploadMessage ===");
        System.out.println("开始消费 Kafka 任务: " + taskId);

        UploadTask task = uploadTaskService.getByTaskId(taskId);
        if (task == null) {
            System.out.println("任务不存在，跳过消费 taskId=" + taskId);
            return;
        }

        if ("SUCCESS".equals(task.getStatus())) {
            System.out.println("任务已成功，跳过重复消费 taskId=" + taskId);
            return;
        }

        if ("PROCESSING".equals(task.getStatus())) {
            System.out.println("任务正在处理中，跳过重复消费 taskId=" + taskId);
            return;
        }

        boolean locked = uploadTaskService.markProcessingIfAllowed(taskId);
        if (!locked) {
            System.out.println("任务状态不允许处理，跳过 taskId=" + taskId);
            return;
        }

        try {
            processUpload(message);

            uploadTaskService.markSuccess(taskId);
            System.out.println("Kafka 消费完成: " + taskId + " 文件: " + message.getFileName());

        } catch (Exception e) {
            handleFailure(message, e);

            System.out.println("Kafka 消费失败: " + taskId + " 文件: " + message.getFileName());
            e.printStackTrace();
        }
    }

    private void processUpload(KafkaUploadMessage message) throws Exception {
        String taskId = message.getTaskId();

        UploadTask task = uploadTaskService.getByTaskId(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在: " + taskId);
        }

        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            throw new RuntimeException("任务文件路径为空: " + taskId);
        }

        String content = fileParseService.parse(task.getFilePath(), task.getFileName());

        String fileName = task.getFileName();
        String docType = task.getDocType() == null || task.getDocType().isBlank()
                ? "UNKNOWN"
                : task.getDocType();

        String uploadStatus = task.getUploadStatus() == null || task.getUploadStatus().isBlank()
                ? "ACTIVE"
                : task.getUploadStatus();

        List<String> chunks = vectorService.splitText(content, docType);

        uploadTaskService.initProgress(taskId, chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            try {
                vectorService.save(
                        chunks.get(i),
                        fileName,
                        i,
                        docType,
                        uploadStatus,
                        null,
                        null,
                        null,
                        null
                );

                uploadTaskService.increaseSuccessChunks(taskId);

            } catch (Exception e) {
                uploadTaskService.increaseFailedChunks(taskId);
                throw e;
            }
        }
    }

    private void handleFailure(KafkaUploadMessage message, Exception e) {
        String taskId = message.getTaskId();
        String errorMsg = e.getMessage() == null ? e.toString() : e.getMessage();

        uploadTaskService.increaseRetryCount(taskId, errorMsg);

        UploadTask latestTask = uploadTaskService.getByTaskId(taskId);
        int retryCount = latestTask.getRetryCount() == null ? 0 : latestTask.getRetryCount();

        if (retryCount >= MAX_RETRY) {
            uploadTaskService.markDead(taskId, errorMsg);
            kafkaProducerService.sendDlqMessage(message);

            System.out.println("任务超过最大重试次数，已进入 DLQ，taskId=" + taskId);
            return;
        }

        uploadTaskService.markFailed(taskId, errorMsg);
        System.out.println("任务失败，等待后续重试，taskId=" + taskId + ", retryCount=" + retryCount);
    }
}