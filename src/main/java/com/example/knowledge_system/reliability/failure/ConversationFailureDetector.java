package com.example.knowledge_system.reliability.failure;

import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import com.example.knowledge_system.reliability.dto.FailureAssessment;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.reliability.model.ConversationState;
import org.springframework.stereotype.Service;

/**
 * Single-turn hard failures only (explicit user rejection, high-risk routing).
 * Multi-turn degradation (repeat questions, weak evidence streak) is handled by
 * {@link EscalationScoreService} via {@code escalationScore}.
 */
@Service
public class ConversationFailureDetector {

    public FailureAssessment detect(String question,
                                    ConversationState state,
                                    RoutingDecision routing,
                                    EvidenceAssessment evidence) {
        FailureAssessment assessment = new FailureAssessment();
        assessment.setShouldHandoff(false);
        assessment.setSeverity("low");

        if (question == null) {
            return assessment;
        }

        String q = question.trim();

        if (containsUserRejection(q)) {
            assessment.setShouldHandoff(true);
            assessment.setSeverity("high");
            assessment.setFailureType("user_rejection");
            assessment.setHandoffReason("user indicated answer was not helpful");
            assessment.setSummaryForAgent(buildSummary(state, q, routing, evidence));
            return assessment;
        }

        if (routing != null && "high".equals(routing.getRiskLevel())) {
            assessment.setShouldHandoff(true);
            assessment.setSeverity("high");
            assessment.setFailureType("high_risk");
            assessment.setHandoffReason("high risk topic requires human agent");
            assessment.setSummaryForAgent(buildSummary(state, q, routing, evidence));
            return assessment;
        }

        return assessment;
    }

    private boolean containsUserRejection(String q) {
        return q.contains("你没回答")
                || q.contains("不是这个意思")
                || q.contains("别复制")
                || q.contains("听不懂")
                || q.contains("没用")
                || q.contains("转人工")
                || q.contains("人工客服");
    }

    private String buildSummary(ConversationState state,
                                String question,
                                RoutingDecision routing,
                                EvidenceAssessment evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题: ").append(question).append("\n");
        if (routing != null) {
            sb.append("路由: ").append(routing.getSuggestedPath())
                    .append(", intent=").append(routing.getIntent()).append("\n");
        }
        if (state != null) {
            sb.append("会话状态: ").append(state.getConversationState())
                    .append(", escalationScore=").append(state.getEscalationScore()).append("\n");
        }
        if (evidence != null) {
            sb.append("证据: sufficient=").append(evidence.isSufficient())
                    .append(", scope=").append(evidence.getAnswerableScope()).append("\n");
        }
        return sb.toString();
    }
}
