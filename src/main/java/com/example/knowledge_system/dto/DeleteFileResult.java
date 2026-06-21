package com.example.knowledge_system.dto;

public class DeleteFileResult {
    private String fileName;
    private int deletedCount;

    public DeleteFileResult(String fileName, int deletedCount){
        this.fileName = fileName;
        this.deletedCount =deletedCount;
    }
    public String getFileName() {
        return fileName;
    }

    public int getDeletedCount() {
        return deletedCount;
    }

}
