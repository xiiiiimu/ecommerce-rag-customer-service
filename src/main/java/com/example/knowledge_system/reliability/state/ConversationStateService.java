package com.example.knowledge_system.reliability.state;

import com.example.knowledge_system.reliability.failure.EscalationScoreService;
import com.example.knowledge_system.reliability.model.ConversationState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;

@Service
public class ConversationStateService {

    private static final String KEY_PREFIX = "rag:state:";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate stringRedisTemplate;
    private final EscalationScoreService escalationScoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationStateService(StringRedisTemplate stringRedisTemplate,
                                    EscalationScoreService escalationScoreService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.escalationScoreService = escalationScoreService;
    }

    public ConversationState load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return newConversationState();
        }
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (json == null || json.isBlank()) {
            return newConversationState();
        }
        try {
            return objectMapper.readValue(json, ConversationState.class);
        } catch (JsonProcessingException e) {
            return newConversationState();
        }
    }

    public void save(String sessionId, ConversationState state) {
        if (sessionId == null || sessionId.isBlank() || state == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    KEY_PREFIX + sessionId,
                    objectMapper.writeValueAsString(state),
                    TTL
            );
        } catch (JsonProcessingException ignored) {
        }
    }

    /**
     * Ends the reliability session: clears escalation/handoff counters so the next
     * conversation with the same sessionId does not inherit prior risk state.
     */
    public void endSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ConversationState state = load(sessionId);
        escalationScoreService.resetAfterHandoff(state);
        state.setTurnCount(0);
        state.setFailureCount(0);
        state.getIntentHistory().clear();
        state.getRecentQuestions().clear();
        state.getRecentAnswerModes().clear();
        save(sessionId, state);
    }

    public void recordTurn(ConversationState state,
                           String normalizedQuestion,
                           String intent,
                           String answerMode) {
        state.setTurnCount(state.getTurnCount() + 1);

        if (intent != null) {
            state.getIntentHistory().add(intent);
            while (state.getIntentHistory().size() > 10) {
                state.getIntentHistory().remove(0);
            }
        }

        if (normalizedQuestion != null) {
            if (isRepeat(state, normalizedQuestion)) {
                state.setUserRepeatedQuestionCount(state.getUserRepeatedQuestionCount() + 1);
            }
            state.getRecentQuestions().add(normalizedQuestion);
            while (state.getRecentQuestions().size() > 3) {
                state.getRecentQuestions().remove(0);
            }
        }

        if (answerMode != null) {
            state.getRecentAnswerModes().add(answerMode);
            while (state.getRecentAnswerModes().size() > 5) {
                state.getRecentAnswerModes().remove(0);
            }
            recordWeakEvidenceAnswer(state, answerMode);
        }

        updateConversationLabel(state);
    }

    /**
     * Counts session weak answers (PARTIAL/INSUFFICIENT evidence with ANSWER outcome).
     * Updated after each turn in {@code finish()}; read next turn by escalation / labels.
     */
    private void recordWeakEvidenceAnswer(ConversationState state, String turnLabel) {
        if (turnLabel == null) {
            return;
        }
        String[] parts = turnLabel.split("\\|", 3);
        if (parts.length < 3) {
            return;
        }
        String evidenceStatus = parts[1];
        String outcome = parts[2];
        if (!"ANSWER".equals(outcome)) {
            return;
        }
        if ("INSUFFICIENT".equals(evidenceStatus) || "PARTIAL".equals(evidenceStatus)) {
            state.setLowConfidenceCount(state.getLowConfidenceCount() + 1);
        }
    }

    private boolean isRepeat(ConversationState state, String normalizedQuestion) {
        for (String recent : state.getRecentQuestions()) {
            if (normalizedQuestion.equals(recent)) {
                return true;
            }
        }
        return false;
    }

    private void updateConversationLabel(ConversationState state) {
        if (state.getEscalationScore() >= 8 || state.isHandoffOffered()) {
            state.setConversationState("ESCALATED");
        } else if (state.getUserRepeatedQuestionCount() >= 2 || state.getLowConfidenceCount() >= 2) {
            state.setConversationState("FRUSTRATED");
        } else if (state.getLowConfidenceCount() >= 1) {
            state.setConversationState("CONFUSED");
        } else {
            state.setConversationState("NORMAL");
        }
    }

    private ConversationState newConversationState() {
        ConversationState state = new ConversationState();
        state.setIntentHistory(new ArrayList<>());
        state.setRiskFlags(new ArrayList<>());
        state.setRecentQuestions(new ArrayList<>());
        state.setRecentAnswerModes(new ArrayList<>());
        return state;
    }
}
