package com.example.knowledge_system.orchestration;

import com.example.knowledge_system.reliability.config.ReliabilityProperties;
import com.example.knowledge_system.reliability.dto.AnswerMode;
import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import com.example.knowledge_system.reliability.dto.EvidenceStatus;
import com.example.knowledge_system.reliability.dto.ExecutionPlan;
import com.example.knowledge_system.reliability.dto.FailureAssessment;
import com.example.knowledge_system.reliability.dto.FinalOutcome;
import com.example.knowledge_system.reliability.dto.RetrievalGateResult;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import com.example.knowledge_system.reliability.model.ConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerServiceTest {

    private PlannerService plannerService;

    @BeforeEach
    void setUp() {
        ReliabilityProperties properties = new ReliabilityProperties();
        properties.setEscalationThreshold(8);
        plannerService = new PlannerService(properties);
    }

    @Test
    void toolQuestionMapsToAdminTool() {
        RoutingDecision routing = routing("tool_call", "tool", false, "low");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.ADMIN_TOOL, plan.getAnswerMode());
        assertEquals(EvidenceStatus.NONE, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertFalse(plan.isUseLlm());
    }

    @Test
    void chitChatMapsToUserTool() {
        RoutingDecision routing = routing("chit_chat", "no_rag", false, "low");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.USER_TOOL, plan.getAnswerMode());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertTrue(plan.isUseLlm());
    }

    @Test
    void pureOrderMapsToOrder() {
        RoutingDecision routing = routing("order", "tool", false, "low");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.ORDER, plan.getAnswerMode());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertFalse(plan.isUseLlm());
    }

    @Test
    void refundWithSufficientEvidenceMapsToOrderWithRagAnswer() {
        RoutingDecision routing = routing("refund", "rag", true, "medium");
        EvidenceAssessment evidence = sufficientEvidence("full", "high");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), evidence, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.ORDER_WITH_RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.HIGH_CONFIDENCE, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertTrue(plan.isUseLlm());
    }

    @Test
    void refundWithInsufficientEvidenceMapsToRefuse() {
        RoutingDecision routing = routing("refund", "rag", true, "medium");
        EvidenceAssessment evidence = insufficientEvidence();
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), evidence, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.ORDER_WITH_RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.INSUFFICIENT, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.REFUSE, plan.getOutcome());
        assertFalse(plan.isUseLlm());
    }

    @Test
    void refundWithConflictingEvidenceMapsToRefuse() {
        RoutingDecision routing = routing("refund", "rag", true, "medium");
        EvidenceAssessment evidence = sufficientEvidence("full", "high");
        evidence.getConflicts().add("policy mismatch");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), evidence, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.ORDER_WITH_RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.CONFLICT, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.REFUSE, plan.getOutcome());
    }

    @Test
    void knowledgeQueryWithHighConfidenceMapsToRagAnswer() {
        RoutingDecision routing = routing("knowledge_query", "rag", true, "low");
        EvidenceAssessment evidence = sufficientEvidence("full", "high");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), evidence, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.HIGH_CONFIDENCE, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertTrue(plan.isUseLlm());
    }

    @Test
    void knowledgeQueryWithPartialEvidenceMapsToRagPartialAnswer() {
        RoutingDecision routing = routing("knowledge_query", "rag", true, "low");
        EvidenceAssessment evidence = sufficientEvidence("partial", "medium");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), evidence, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.PARTIAL, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
        assertTrue(plan.isUseLlm());
    }

    @Test
    void highRiskComplaintMapsToHandoffWithUnknownMode() {
        RoutingDecision routing = routing("complaint", "handoff", false, "high");
        FailureAssessment failure = failure(true);
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure, new ConversationState(), 5);
        assertEquals(AnswerMode.UNKNOWN, plan.getAnswerMode());
        assertEquals(FinalOutcome.HANDOFF, plan.getOutcome());
        assertFalse(plan.isUseLlm());
    }

    @Test
    void escalationThresholdMapsToHandoffPreservingAnswerMode() {
        RoutingDecision routing = routing("knowledge_query", "rag", true, "low");
        ConversationState state = new ConversationState();
        state.setHandoffOffered(true);
        ExecutionPlan plan = plannerService.plan(
                routing, gate(true), sufficientEvidence("full", "high"),
                failure(false), state, 5);
        assertEquals(AnswerMode.RAG, plan.getAnswerMode());
        assertEquals(EvidenceStatus.HIGH_CONFIDENCE, plan.getEvidenceStatus());
        assertEquals(FinalOutcome.HANDOFF, plan.getOutcome());
    }

    @Test
    void handoffOnTransferHumanUsesUnknownMode() {
        RoutingDecision routing = routing("unknown", "rag", false, "low");
        FailureAssessment failure = failure(true);
        failure.setHandoffReason("user requested human agent");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure, new ConversationState(), 0);
        assertEquals(AnswerMode.UNKNOWN, plan.getAnswerMode());
        assertEquals(FinalOutcome.HANDOFF, plan.getOutcome());
    }

    @Test
    void unknownIntentMapsToUnknownAnswerFallback() {
        RoutingDecision routing = routing("unknown", "clarify", false, "low");
        ExecutionPlan plan = plannerService.plan(
                routing, gate(false), null, failure(false), new ConversationState(), 0);
        assertEquals(AnswerMode.UNKNOWN, plan.getAnswerMode());
        assertEquals(FinalOutcome.ANSWER, plan.getOutcome());
    }

    @Test
    void resolveEvidenceStatusMapsCorrectly() {
        assertEquals(EvidenceStatus.NONE, PlannerService.resolveEvidenceStatus(null));

        EvidenceAssessment insufficient = insufficientEvidence();
        assertEquals(EvidenceStatus.INSUFFICIENT, PlannerService.resolveEvidenceStatus(insufficient));

        EvidenceAssessment conflict = sufficientEvidence("full", "high");
        conflict.getConflicts().add("x");
        assertEquals(EvidenceStatus.CONFLICT, PlannerService.resolveEvidenceStatus(conflict));

        EvidenceAssessment partial = sufficientEvidence("partial", "medium");
        assertEquals(EvidenceStatus.PARTIAL, PlannerService.resolveEvidenceStatus(partial));

        EvidenceAssessment high = sufficientEvidence("full", "high");
        assertEquals(EvidenceStatus.HIGH_CONFIDENCE, PlannerService.resolveEvidenceStatus(high));
    }

    private static RoutingDecision routing(String intent,
                                           String path,
                                           boolean needRetrieval,
                                           String riskLevel) {
        RoutingDecision routing = new RoutingDecision();
        routing.setIntent(intent);
        routing.setSuggestedPath(path);
        routing.setNeedRetrieval(needRetrieval);
        routing.setRiskLevel(riskLevel);
        routing.setReason("test");
        return routing;
    }

    private static RetrievalGateResult gate(boolean shouldRetrieve) {
        RetrievalGateResult gate = new RetrievalGateResult();
        gate.setShouldRetrieve(shouldRetrieve);
        gate.setReason(shouldRetrieve ? "open" : "closed");
        return gate;
    }

    private static FailureAssessment failure(boolean shouldHandoff) {
        FailureAssessment failure = new FailureAssessment();
        failure.setShouldHandoff(shouldHandoff);
        return failure;
    }

    private static EvidenceAssessment sufficientEvidence(String scope, String confidence) {
        EvidenceAssessment evidence = new EvidenceAssessment();
        evidence.setSufficient(true);
        evidence.setAnswerableScope(scope);
        evidence.setConfidence(confidence);
        evidence.setReason("ok");
        return evidence;
    }

    private static EvidenceAssessment insufficientEvidence() {
        EvidenceAssessment evidence = new EvidenceAssessment();
        evidence.setSufficient(false);
        evidence.setAnswerableScope("none");
        evidence.setConfidence("low");
        evidence.setReason("no evidence");
        return evidence;
    }
}
