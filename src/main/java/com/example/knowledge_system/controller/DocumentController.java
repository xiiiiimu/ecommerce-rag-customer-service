package com.example.knowledge_system.controller;
import org.apache.tika.Tika;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.example.knowledge_system.entity.KnowledgeDocument;
import com.example.knowledge_system.service.DocumentService;
import com.example.knowledge_system.entity.DocumentChunk;
import com.example.knowledge_system.util.TextSplitter;

import org.apache.tika.Tika;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Text;

import javax.swing.text.Document;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/documents")
public class DocumentController {
    private final DocumentService documentService;
    public DocumentController(DocumentService documentService){
        this.documentService = documentService;
    }

    @GetMapping("/search")
    public List<DocumentChunk> search(@RequestParam("keyword") String keyword){
        return documentService.searchChunks(keyword);
    }
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception{
        Map<String, Object> result  = new HashMap<>();

        Tika tika = new Tika();
        String content = tika.parseToString(file.getInputStream());

        List<String> chunks = TextSplitter.split(content, 200);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setFilename(file.getOriginalFilename());
        document.setContentType(file.getContentType());
        document.setSize(file.getSize());
        document.setContent(content);
        document.setUploadTime(LocalDateTime.now());

        KnowledgeDocument savedDocument = documentService.saveDocument(document);
        documentService.saveChunks(savedDocument.getId(), chunks);

        result.put("id", savedDocument.getId());
        result.put("fileName", savedDocument.getFileName());
        result.put("size", savedDocument.getSize());
        result.put("contentType", savedDocument.getContentType());
        result.put("content", savedDocument.getContent());
        result.put("uploadTime", savedDocument.getUploadTime());
        result.put("chunkCount", chunks.size());
        result.put("chunks", chunks);
        result.put("success", true);

        return result;
    }

}
