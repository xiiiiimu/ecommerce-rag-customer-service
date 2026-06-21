package com.example.knowledge_system.entity;

import java.time.LocalDateTime;

public class UploadTask {

    private Long id;
    private String taskId;
    private String fileName;
    private String status;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private Integer totalChunks;
    private Integer processedChunks;
    private Integer successChunks;
    private Integer failedChunks;
    private Integer retryCount;
    private String filePath;
    private String docType;
    private String uploadStatus;


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public Integer getProcessedChunks() {
        return processedChunks;
    }

    public void setProcessedChunks(Integer processedChunks) {
        this.processedChunks = processedChunks;
    }

    public Integer getSuccessChunks() {
        return successChunks;
    }

    public void setSuccessChunks(Integer successChunks) {
        this.successChunks = successChunks;
    }

    public Integer getFailedChunks() {
        return failedChunks;
    }

    public void setFailedChunks(Integer failedChunks) {
        this.failedChunks = failedChunks;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }


    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getUploadStatus() {
        return uploadStatus;
    }

    public void setUploadStatus(String uploadStatus) {
        this.uploadStatus = uploadStatus;
    }
}