package com.example.knowledge_system.dto;

public class MultiUploadResult {
    private int fileCount;
    private int totalChunks;
    private int insertedChunks;
    private int skippedChunks;

    public MultiUploadResult(int fileCount, int totalChunks,int insertedChunks, int skippedChunks){
        this.fileCount = fileCount;
        this.totalChunks = totalChunks;
        this.insertedChunks = skippedChunks;
    }

    public int getFileCount(){
        return fileCount;
    }
    public int getTotalChunks() {
        return totalChunks;
    }

    public int getInsertedChunks() {
        return insertedChunks;
    }

    public int getSkippedChunks() {
        return skippedChunks;
    }


}
