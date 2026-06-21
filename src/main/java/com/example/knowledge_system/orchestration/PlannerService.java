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
import org.springframework.stereotype.Service;

@Service
public class PlannerService {

    private final ReliabilityProperties properties;

    public PlannerService(ReliabilityProperties properties) {
        this.properties = properties;
    }

    public ExecutionPlan plan(RoutingDecision routing,
                              RetrievalGateResult gate,
                              EvidenceAssessment evidence,
                              FailureAssessment failure,
                              ConversationState state,
                              int escalationScore) {
        EvidenceStatus evidenceStatus = resolveEvidenceStatus(evidence);

        if (shouldHandoff(failure, state, escalationScore)) {
            return handoffPlan(routing, evidenceStatus, "failure or escalation threshold");
        }

        if (routing == null) {
            return answerPlan(AnswerMode.UNKNOWN, EvidenceStatus.NONE, FinalOutcome.REFUSE,
                    false, "missing routing");
        }

        return switch (routing.getIntent()) {
            case "complaint" -> handoffPlan(routing, EvidenceStatus.NONE,
                    routing.getReason() == null ? "high risk complaint" : routing.getReason());
            case "unknown" -> refusePlan(AnswerMode.UNKNOWN, EvidenceStatus.NONE,
                    routing.getReason() == null ? "unknown intent" : routing.getReason());
            case "tool_call" -> buildAdminToolPlan(gate, evidence);
            case "rewrite", "summarize", "chit_chat" -> answerPlan(AnswerMode.USER_TOOL,
                    EvidenceStatus.NONE, FinalOutcome.ANSWER, true,
                    "user tool: rewrite/summarize/chitchat");
            case "order" -> routing.isNeedRetrieval()
                    ? buildOrderWithRagPlan(evidence)
                    : answerPlan(AnswerMode.ORDER, EvidenceStatus.NONE, FinalOutcome.ANSWER,
                            false, "deterministic order lookup");
            case "refund" -> buildOrderWithRagPlan(evidence);
            case "knowledge_query" -> buildKnowledgeQueryPlan(gate, evidence);
            default -> answerPlan(AnswerMode.UNKNOWN, EvidenceStatus.NONE, FinalOutcome.REFUSE,
                    false, routing.getReason() != null ? routing.getReason() : "unclassified intent");
        };
    }

    private boolean shouldHandoff(FailureAssessment failure,
                                  ConversationState state,
                                  int escalationScore) {
        if (failure != null && failure.isShouldHandoff()) {
            return true;
        }
        if (state != null && state.isHandoffOffered()) {
            return true;
        }
        return escalationScore >= properties.getEscalationThreshold();
    }

    private ExecutionPlan handoffPlan(RoutingDecision routing,
                                      EvidenceStatus evidenceStatus,
                                      String reason) {
        ExecutionPlan plan = new ExecutionPlan();
        plan.setAnswerMode(inferAnswerMode(routing));
        plan.setEvidenceStatus(evidenceStatus);
        plan.setOutcome(FinalOutcome.HANDOFF);
        plan.setUseLlm(false);
        plan.setReason(reason);
        return plan;
    }

    private AnswerMode inferAnswerMode(RoutingDecision routing) {
        if (routing == null) {
            return AnswerMode.UNKNOWN;
        }
        return switch (routing.getIntent()) {
            case "tool_call" -> AnswerMode.ADMIN_TOOL;
            case "rewrite", "summarize", "chit_chat" -> AnswerMode.USER_TOOL;
            case "order" -> routing.isNeedRetrieval() ? AnswerMode.ORDER_WITH_RAG : AnswerMode.ORDER;
            case "refund" -> AnswerMode.ORDER_WITH_RAG;
            case "knowledge_query" -> AnswerMode.RAG;
            default -> AnswerMode.UNKNOWN;
        };
    }

    private ExecutionPlan buildOrderWithRagPlan(EvidenceAssessment evidence) {
        return buildEvidenceGatedPlan(AnswerMode.ORDER_WITH_RAG, evidence,
                "order with policy evidence");
    }

    private ExecutionPlan buildAdminToolPlan(RetrievalGateResult gate,
                                             EvidenceAssessment evidence) {
        if (gate == null || !gate.isShouldRetrieve()) {
            return refusePlan(AnswerMode.ADMIN_TOOL, EvidenceStatus.INSUFFICIENT,
                    "retrieval gate closed for rag_with_tool");
        }
        if (evidence == null) {
            return answerPlan(AnswerMode.ADMIN_TOOL, EvidenceStatus.HIGH_CONFIDENCE, FinalOutcome.ANSWER,
                    false, "admin tool with retrieval");
        }
        return buildEvidenceGatedPlan(AnswerMode.ADMIN_TOOL, evidence, "admin tool with retrieval");
    }

    private ExecutionPlan buildKnowledgeQueryPlan(RetrievalGateResult gate,
                                                  EvidenceAssessment evidence) {
        if (gate == null || !gate.isShouldRetrieve()) {
            return refusePlan(AnswerMode.RAG, EvidenceStatus.INSUFFICIENT,
                    "retrieval gate closed for knowledge_query");
        }
        if (evidence == null) {
            return answerPlan(AnswerMode.RAG, EvidenceStatus.HIGH_CONFIDENCE, FinalOutcome.ANSWER,
                    true, "retrieve then generate");
        }
        return buildEvidenceGatedPlan(AnswerMode.RAG, evidence, "knowledge base query");
    }

    private ExecutionPlan buildEvidenceGatedPlan(AnswerMode answerMode,
                                               EvidenceAssessment evidence,
                                               String contextReason) {
        EvidenceStatus status = resolveEvidenceStatus(evidence);
        if (status == EvidenceStatus.INSUFFICIENT || status == EvidenceStatus.CONFLICT) {
            return refusePlan(answerMode, status, contextReason + ": " + status.name().toLowerCase());
        }
        boolean useLlm = status == EvidenceStatus.PARTIAL
                || status == EvidenceStatus.HIGH_CONFIDENCE
                || status == EvidenceStatus.NONE;
        return answerPlan(answerMode, status, FinalOutcome.ANSWER, useLlm, contextReason);
    }

    static EvidenceStatus resolveEvidenceStatus(EvidenceAssessment evidence) {
        if (evidence == null) {
            return EvidenceStatus.NONE;
        }
        if (!evidence.getConflicts().isEmpty()) {
            return EvidenceStatus.CONFLICT;
        }
        if (!evidence.isSufficient() || "none".equals(evidence.getAnswerableScope())) {
            return EvidenceStatus.INSUFFICIENT;
        }
        if ("partial".equals(evidence.getAnswerableScope())
                || "medium".equals(evidence.getConfidence())) {
            return EvidenceStatus.PARTIAL;
        }
        return EvidenceStatus.HIGH_CONFIDENCE;
    }

    private ExecutionPlan answerPlan(AnswerMode answerMode,
                                     EvidenceStatus evidenceStatus,
                                     FinalOutcome outcome,
                                     boolean useLlm,
                                     String reason) {
        ExecutionPlan plan = new ExecutionPlan();
        plan.setAnswerMode(answerMode);
        plan.setEvidenceStatus(evidenceStatus);
        plan.setOutcome(outcome);
        plan.setUseLlm(useLlm);
        plan.setReason(reason);
        return plan;
    }

    private ExecutionPlan refusePlan(AnswerMode answerMode,
                                     EvidenceStatus evidenceStatus,
                                     String reason) {
        return answerPlan(answerMode, evidenceStatus, FinalOutcome.REFUSE, false, reason);
    }
}
