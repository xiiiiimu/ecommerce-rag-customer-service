package com.example.knowledge_system.repository;
import com.example.knowledge_system.entity.DocumentChunk;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
public interface DocumentChunkRepository extends MongoRepository<DocumentChunk, String>{
    List<DocumentChunk> findByDocumentId(String documentId);
    List<DocumentChunk> findByContentContaining(String keyword);
    List<DocumentChunk> findByContentContainingIgnoreCase(String keyword);
}

