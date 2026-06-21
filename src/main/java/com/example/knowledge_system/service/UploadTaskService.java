package com.example.knowledge_system.service;

import com.example.knowledge_system.entity.UploadTask;
import com.example.knowledge_system.mapper.UploadTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class UploadTaskService {

    private final UploadTaskMapper uploadTaskMapper;

    public UploadTaskService(UploadTaskMapper uploadTaskMapper) {
        this.uploadTaskMapper = uploadTaskMapper;
    }

    public void createTask(String taskId,
                           String fileName,
                           String filePath,
                           String docType,
                           String status) {
        UploadTask task = new UploadTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setFilePath(filePath);
        task.setDocType(docType);
        task.setUploadStatus(status);
        task.setStatus("PENDING");
        task.setErrorMsg(null);
        task.setCreateTime(LocalDateTime.now());
        task.setFinishTime(null);

        uploadTaskMapper.insert(task);
    }
    public boolean markProcessingIfAllowed(String taskId) {
        int rows = uploadTaskMapper.markProcessingIfAllowed(taskId);
        System.out.println("markProcessingIfAllowed taskId=" + taskId + ", updatedRows=" + rows);
        return rows > 0;
    }

    public void initProgress(String taskId, Integer totalChunks) {
        uploadTaskMapper.initProgress(taskId, totalChunks);
    }

    public void increaseSuccessChunks(String taskId) {
        uploadTaskMapper.increaseSuccessChunks(taskId);
    }

    public void increaseFailedChunks(String taskId) {
        uploadTaskMapper.increaseFailedChunks(taskId);
    }

    public void increaseRetryCount(String taskId, String errorMsg) {
        uploadTaskMapper.increaseRetryCount(taskId, errorMsg);
    }
    public void markProcessing(String taskId) {
        int rows = uploadTaskMapper.updateStatus(taskId, "PROCESSING", null);
        System.out.println("markProcessing taskId=" + taskId + ", updatedRows=" + rows);
    }

    public void markSuccess(String taskId) {
        int rows = uploadTaskMapper.updateStatus(taskId, "SUCCESS", null);
        System.out.println("markSuccess taskId=" + taskId + ", updatedRows=" + rows);
    }

    public void markFailed(String taskId, String errorMsg) {
        int rows = uploadTaskMapper.updateStatus(taskId, "FAILED", errorMsg);
        System.out.println("markFailed taskId=" + taskId + ", updatedRows=" + rows + ", error=" + errorMsg);
    }

    public UploadTask getByTaskId(String taskId) {
        return uploadTaskMapper.findByTaskId(taskId);
    }

    public void markDead(String taskId, String errorMsg) {
        int rows = uploadTaskMapper.markDead(taskId, errorMsg);
        System.out.println("markDead taskId=" + taskId + ", updatedRows=" + rows + ", error=" + errorMsg);
    }

    public boolean canRetry(String taskId) {
        UploadTask task = uploadTaskMapper.findByTaskId(taskId);

        if (task == null) {
            return false;
        }

        return "FAILED".equals(task.getStatus()) || "DEAD".equals(task.getStatus());
    }

    public void resetToPending(String taskId) {
        uploadTaskMapper.updateStatus(taskId, "PENDING", null);
    }
}