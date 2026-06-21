package com.example.knowledge_system.dto;

public class UploadResult {
    private int total;
    private int inserted;
    private int skipped;

    public UploadResult(int total, int inserted,int skipped){
        this.total = total;
        this.inserted = inserted;
        this.skipped = skipped;
    }

    public int getTotal(){
        return total;
    }

    public int getInserted(){
        return inserted;
    }

    public int getSkipped(){
        return skipped;
    }
}
