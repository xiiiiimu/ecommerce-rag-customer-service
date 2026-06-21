package com.example.knowledge_system.reliability.failure;

import com.example.knowledge_system.reliability.config.ReliabilityProperties;
import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import com.example.knowledge_system.reliability.dto.FailureAssessment;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.reliability.model.ConversationState;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class EscalationScoreService {

    private final ReliabilityProperties properties;

    public EscalationScoreService(ReliabilityProperties properties) {
        this.properties = properties;
    }

  /**
   * Accumulates escalation risk for sessions that have not already failed this turn.
   * When {@link FailureAssessment#isShouldHandoff()} is true, no further increments are applied;
   * the current score after decay is persisted and returned so metrics stay honest.
   */
    public int updateAndGetScore(ConversationState state,
                                 String question,
                                 RoutingDecision routing,
                                 FailureAssessment failure,
                                 EvidenceAssessment evidence) {
        int score = Math.max(0, state.getEscalationScore() - properties.getEscalationDecayPerTurn());

        if (failure != null && failure.isShouldHandoff()) {
            state.setEscalationScore(score);
            state.setHandoffOffered(true);
            state.setConversationState("ESCALATED");
            return score;
        }

        if (state.getUserRepeatedQuestionCount() > 0) {
            score += 2;
        }

        if (routing != null && "high".equals(routing.getRiskLevel())) {
            score += 5;
            state.getRiskFlags().add("high_risk");
        }

        if (evidence != null && !evidence.isSufficient()) {
            score += 3;
        }

        score += lowConfidenceEscalationBonus(state.getLowConfidenceCount());

        if (question != null && (question.contains("投诉") || question.contains("赔偿"))) {
            score += 2;
        }

        state.setEscalationScore(score);

        if (score >= properties.getEscalationThreshold()) {
            state.setHandoffOffered(true);
            state.setConversationState("ESCALATED");
        }

        return score;
    }

    /**
     * Maps session weak-answer count (see {@link ConversationState#getLowConfidenceCount()})
     * into escalation score. Handoff is decided only via threshold in {@link #updateAndGetScore}.
     */
    private int lowConfidenceEscalationBonus(int lowConfidenceCount) {
        if (lowConfidenceCount >= 3) {
            return 3;
        }
        if (lowConfidenceCount >= 2) {
            return 2;
        }
        if (lowConfidenceCount >= 1) {
            return 1;
        }
        return 0;
    }

    public boolean shouldHandoff(ConversationState state) {
        return state.getEscalationScore() >= properties.getEscalationThreshold();
    }

    /**
     * Resets escalation-related session fields after a handoff episode (e.g. new agent session).
     */
    public void resetAfterHandoff(ConversationState state) {
        if (state == null) {
            return;
        }
        state.setEscalationScore(0);
        state.setLowConfidenceCount(0);
        state.setUserRepeatedQuestionCount(0);
        state.setHandoffOffered(false);
        state.setConversationState("NORMAL");
        state.setRiskFlags(new ArrayList<>());
    }
}
