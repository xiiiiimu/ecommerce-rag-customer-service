package com.example.knowledge_system.entity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection ="document_chunk")
public class DocumentChunk {
    @Id
    private String id;
    private String documentId;
    private Integer chunkIndex;
    private String content;
    private List<Double> embedding;

    public String getId(){
        return id;
    }

    public String getDocumentId(){
        return documentId;
    }

    public Integer getChunkIndex(){
        return chunkIndex;
    }

    public String getContent(){
        return content;
    }
    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setId(String id){
        this.id = id;
    }

    public void setDocumentId(String documentId){
        this.documentId = documentId;
    }

    public void setChunkIndex(Integer chunkIndex){
        this.chunkIndex = chunkIndex;
    }

    public void setContent(String content){
        this.content = content;
    }
    public void setEmbedding(List<Double> embedding) {
        this.embedding = embedding;
    }



}
