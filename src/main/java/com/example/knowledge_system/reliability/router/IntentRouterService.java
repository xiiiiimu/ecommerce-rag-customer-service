package com.example.knowledge_system.reliability.router;

import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.reliability.model.ConversationState;
import com.example.knowledge_system.service.VectorService;
import org.springframework.stereotype.Service;

@Service
public class IntentRouterService {

    private final VectorService vectorService;

    public IntentRouterService(VectorService vectorService) {
        this.vectorService = vectorService;
    }

    public RoutingDecision route(String question, ConversationState state) {
        RoutingDecision decision = new RoutingDecision();

        if (question == null || question.isBlank()) {
            decision.setIntent("unknown");
            decision.setNeedRetrieval(false);
            decision.setRiskLevel("low");
            decision.setRequiresTool(false);
            decision.setRequiresClarification(false);
            decision.setSuggestedPath("unknown");
            decision.setReason("empty question");
            decision.setRuleConfidence(1.0);
            return decision;
        }

        String q = question.trim();

        if (isHighRisk(q)) {
            decision.setIntent("complaint");
            decision.setNeedRetrieval(false);
            decision.setRiskLevel("high");
            decision.setRequiresTool(false);
            decision.setRequiresClarification(false);
            decision.setSuggestedPath("handoff");
            decision.setReason("high risk keywords detected");
            decision.setRuleConfidence(0.95);
            return decision;
        }

        if (isNoRag(q)) {
            decision.setIntent(detectNoRagIntent(q));
            decision.setNeedRetrieval(false);
            decision.setRiskLevel("low");
            decision.setRequiresTool(false);
            decision.setRequiresClarification(false);
            decision.setSuggestedPath("no_rag");
            decision.setReason("rewrite/summarize/chitchat, no knowledge base needed");
            decision.setRuleConfidence(0.9);
            return decision;
        }

        if (isToolQuestion(q)) {
            decision.setIntent("tool_call");
            decision.setNeedRetrieval(true);
            decision.setRiskLevel("low");
            decision.setRequiresTool(true);
            decision.setRequiresClarification(false);
            decision.setSuggestedPath("rag_with_tool");
            decision.setReason("knowledge base admin query needs retrieval and tool execution");
            decision.setRuleConfidence(0.92);
            return decision;
        }

        if (vectorService.isOrderQuestionForRouter(q)) {
            boolean needRule = vectorService.needRuleForOrderQuestionForRouter(q);
            decision.setIntent(needRule ? "refund" : "order");
            decision.setNeedRetrieval(needRule);
            decision.setRiskLevel(needRule ? "medium" : "low");
            decision.setRequiresTool(!needRule);
            decision.setRequiresClarification(false);
            decision.setSuggestedPath(needRule ? "order_with_rag" : "order_tool");
            decision.setReason(needRule
                    ? "order question needs policy docs and RAG answer"
                    : "deterministic order lookup");
            decision.setRuleConfidence(0.88);
            return decision;
        }

        decision.setIntent("knowledge_query");
        decision.setNeedRetrieval(true);
        decision.setRiskLevel("low");
        decision.setRequiresTool(false);
        decision.setRequiresClarification(false);
        decision.setSuggestedPath("rag");
        decision.setReason("knowledge base query (policy, docs, uploaded files)");
        decision.setRuleConfidence(0.85);
        return decision;
    }

    private boolean isHighRisk(String q) {
        return q.contains("赔偿")
                || q.contains("投诉")
                || q.contains("封号")
                || q.contains("律师")
                || q.contains("起诉")
                || q.contains("自动续费")
                || q.contains("乱扣费")
                || q.contains("盗刷");
    }

    private boolean isNoRag(String q) {
        return q.contains("帮我改写")
                || q.contains("改写一下")
                || q.contains("总结一下刚才")
                || q.contains("总结上文")
                || q.contains("翻译成")
                || q.matches("^(你好|您好|谢谢|感谢|在吗)[\\?？!！。]*$");
    }

    private String detectNoRagIntent(String q) {
        if (q.contains("翻译")) {
            return "rewrite";
        }
        if (q.contains("总结")) {
            return "summarize";
        }
        if (q.matches("^(你好|您好|谢谢|感谢|在吗)[\\?？!！。]*$")) {
            return "chit_chat";
        }
        return "rewrite";
    }

    private boolean isToolQuestion(String q) {
        return q.contains("有哪些文件")
                || q.contains("文件列表")
                || (q.contains("版本") && (q.contains(".txt") || q.contains(".pdf")))
                || ((q.contains("删除文件") || q.contains("删除文档")) && q.contains("删除"))
                || (q.contains("任务") && q.contains("状态"));
    }

    // Ambiguous short-text fallback is intentionally disabled:
    // short business questions (e.g. "退款", "物流", "发货") should default to knowledge_query/RAG.
}
