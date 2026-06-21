package com.example.knowledge_system.service;

import com.example.knowledge_system.entity.DocumentChunk;
import com.example.knowledge_system.entity.KnowledgeDocument;
import com.example.knowledge_system.repository.DocumentChunkRepository;
import com.example.knowledge_system.repository.KnowledgeDocumentRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DocumentService {
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingService embeddingService;

    public DocumentService(KnowledgeDocumentRepository knowledgeDocumentRepository, DocumentChunkRepository documentChunkRepository, EmbeddingService embeddingService){
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingService = embeddingService;
    }
    public KnowledgeDocument saveDocument(KnowledgeDocument document){
        return knowledgeDocumentRepository.save(document);
    }


    public void saveChunks(String documentId, List<String> chunks){
        for(int i = 0; i < chunks.size(); i++){
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setEmbedding(embeddingService.embed(chunks.get(i)));
            documentChunkRepository.save(chunk);
        }
    }
    public List<DocumentChunk> searchChunks(String question){
        String[] keywords = question
                .toLowerCase()
                .split("\\s+");
        List<DocumentChunk> results = new ArrayList<>();

        for(String keyword : keywords){
            if(keyword.length() < 3) continue;

            List<DocumentChunk> found = documentChunkRepository.findByContentContainingIgnoreCase(keyword);
            results.addAll(found);
        }

        List<DocumentChunk> distinctResults = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for(DocumentChunk chunk : results){
            if(chunk.getId() != null && !seenIds.contains(chunk.getId())){
                seenIds.add(chunk.getId());
                distinctResults.add(chunk);
            }
        }

        distinctResults.sort((a, b) -> {
            int scoreA = calculateScore(a.getContent(), keywords);
            int scoreB = calculateScore(b.getContent(), keywords);
            return Integer.compare(scoreB, scoreA);
        });

        if(results.isEmpty()){
            return documentChunkRepository.findAll().stream().limit(5).toList();
        }

        return distinctResults.stream().limit(5).toList();
    }

    private int  calculateScore(String content, String[] keywords){
        if(content == null || content.isEmpty()){
            return 0;
        }

        String lowerContent = content.toLowerCase();
        int score = 0;

        for(String keyword : keywords){
            if(keyword.length() < 3){
                continue;
            }
            int index = 0;
            while((index = lowerContent.indexOf(keyword, index)) != -1){
                score++;
                index += keyword.length();
            }
        }
        return score;
    }

    public List<DocumentChunk> semanticSearch(String question){
        List<Double> questionEmbedding = embeddingService.embed(question);
        List<DocumentChunk> allChunks = documentChunkRepository.findAll();

        List<DocumentChunkScore> scoredChunks = new ArrayList<>();

        for(DocumentChunk chunk : allChunks){
            double score = embeddingService.cosineSimilarity(questionEmbedding, chunk.getEmbedding());
            scoredChunks.add(new DocumentChunkScore(chunk, score));
        }

        scoredChunks.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<DocumentChunk> result = new ArrayList<>();
        for(int i = 0; i < Math.min(5, scoredChunks.size()); i++){
            result.add(scoredChunks.get(i).chunk());
        }
        return result;
    }
    private record DocumentChunkScore(DocumentChunk chunk, double score) {
    }

}
