package com.example.knowledge_system.orchestration;

import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.service.VectorService;
import org.springframework.stereotype.Service;

@Service
public class NoRagHandlerService {

    private final VectorService vectorService;

    public NoRagHandlerService(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    public AskResult handle(String sessionId, String question, RoutingDecision routing) {
        String intent = routing == null ? "unknown" : routing.getIntent();

        if ("chit_chat".equals(intent)) {
            return AskResult.of(
                    "您好，我是电商智能客服，可为您解答订单、退款、物流、优惠券与活动规则等问题。");
        }

        if ("rewrite".equals(intent) || "summarize".equals(intent)) {
            String answer = vectorService.generateNoRagReply(sessionId, question, intent);
            return AskResult.of(answer);
        }

        return AskResult.of("请补充更具体的问题，例如订单号、商品名称或想了解的政策类型。");
    }
}
