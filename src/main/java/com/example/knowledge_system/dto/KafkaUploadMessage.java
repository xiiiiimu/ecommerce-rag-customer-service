package com.example.knowledge_system.dto;

public class KafkaUploadMessage {
    private String taskId;
    private String fileName;
    private String content;
    private String status;
    private String startTime;
    private String endTime;
    private String docType;

    public KafkaUploadMessage(){
    }

    public KafkaUploadMessage(String taskId, String fileName, String content){
        this.taskId = taskId;
        this.fileName = fileName;
        this.content = content;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }
}
