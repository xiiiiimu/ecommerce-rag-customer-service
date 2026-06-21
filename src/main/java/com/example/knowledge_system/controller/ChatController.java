package com.example.knowledge_system.controller;

import com.example.knowledge_system.dto.AskRequest;
import com.example.knowledge_system.dto.AskResponse;
import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.orchestration.RagOrchestrator;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final RagOrchestrator ragOrchestrator;

    public ChatController(RagOrchestrator ragOrchestrator) {
        this.ragOrchestrator = ragOrchestrator;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        AskResult result = ragOrchestrator.ask(
                request.getSessionId(),
                request.getQuestion()
        );

        AskResponse response = new AskResponse();
        response.setQuestion(request.getQuestion());
        response.setAnswer(result.getAnswer());
        response.setChunks(result.getChunks());
        return response;
    }

    @PostMapping("/session/end")
    public void endSession(@RequestBody AskRequest request) {
        ragOrchestrator.endSession(request.getSessionId());
    }
}
