package com.example.knowledge_system.dto;

import com.example.knowledge_system.entity.DocumentChunk;

import java.util.List;

public class AskResponse {
    private String question;
    private String answer;

    private List<DocumentChunkVO> chunks;

    public String getQuestion(){
        return question;
    }

    public void setQuestion(String question){
        this.question = question;
    }

    public String getAnswer(){
        return answer;
    }

    public void setAnswer(String answer){
        this.answer = answer;
    }

    public List<DocumentChunkVO> getChunks() {
        return chunks;
    }

    public void setChunks(List<DocumentChunkVO> chunks) {
        this.chunks = chunks;
    }

}
