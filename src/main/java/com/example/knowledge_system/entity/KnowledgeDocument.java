package com.example.knowledge_system.entity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

@Document(collation = "knowledge_document")
public class KnowledgeDocument {
    @Id
    private String id;
    private String fileName;
    private String contentType;
    private Long size;
    private String content;
    private LocalDateTime uploadTime;

    public String getId(){
        return id;
    }

    public void setId(String id){
        this.id = id;
    }
    public String getFileName(){
        return fileName;
    }
    public void setFilename(String fileName){
        this.fileName = fileName;
    }
    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }

}
