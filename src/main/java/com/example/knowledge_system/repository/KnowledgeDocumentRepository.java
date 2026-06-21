package com.example.knowledge_system.repository;

import com.example.knowledge_system.entity.KnowledgeDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {
}
