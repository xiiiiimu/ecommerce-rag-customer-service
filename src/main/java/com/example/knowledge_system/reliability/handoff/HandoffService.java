package com.example.knowledge_system.reliability.handoff;

import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import com.example.knowledge_system.reliability.dto.FailureAssessment;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.reliability.model.ConversationState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class HandoffService {

    public AskResult buildHandoffResponse(String question,
                                          ConversationState state,
                                          RoutingDecision routing,
                                          EvidenceAssessment evidence,
                                          FailureAssessment failure,
                                          List<DocumentChunkVO> chunks,
                                          List<String> attemptedActions) {
        String summary = failure != null && failure.getSummaryForAgent() != null
                ? failure.getSummaryForAgent()
                : "用户问题需要人工进一步处理。";

        StringBuilder answer = new StringBuilder();
        answer.append("已为您转接人工客服，请稍候。\n\n");
        answer.append("【转接原因】").append(resolveReason(failure, routing)).append("\n");
        answer.append("【您的问题】").append(question).append("\n");
        if (state != null) {
            answer.append("【会话状态】").append(state.getConversationState())
                    .append("，升级分=").append(state.getEscalationScore()).append("\n");
        }
        if (!attemptedActions.isEmpty()) {
            answer.append("【已尝试】").append(String.join("；", attemptedActions)).append("\n");
        }
        if (evidence != null && chunks != null && !chunks.isEmpty()) {
            answer.append("【检索摘要】").append(chunks.get(0).getFileName())
                    .append(" / ").append(preview(chunks.get(0).getContent())).append("\n");
        }
        answer.append("\n人工客服将基于以上摘要继续为您服务。");

        return AskResult.of(answer.toString(), chunks == null ? List.of() : chunks);
    }

    private String resolveReason(FailureAssessment failure, RoutingDecision routing) {
        if (failure != null && failure.getHandoffReason() != null) {
            return failure.getHandoffReason();
        }
        if (routing != null && routing.getReason() != null) {
            return routing.getReason();
        }
        return "conversation escalation";
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String text = content.replace("\n", " ");
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }
}
