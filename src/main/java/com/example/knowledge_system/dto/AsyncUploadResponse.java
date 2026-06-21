package com.example.knowledge_system.dto;

public class AsyncUploadResponse {
    private String taskId;
    private String message;

    public AsyncUploadResponse(String taskId, String message){
        this.taskId =taskId;
        this.message = message;
    }

    public String getTaskId(){
        return taskId;
    }
    public String getMessage(){
        return message;
    }
}
