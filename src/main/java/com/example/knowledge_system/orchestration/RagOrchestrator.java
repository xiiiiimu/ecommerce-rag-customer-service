package com.example.knowledge_system.orchestration;

import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.reliability.config.ReliabilityProperties;
import com.example.knowledge_system.reliability.dto.*;
import com.example.knowledge_system.reliability.evidence.EvidenceSufficiencyService;
import com.example.knowledge_system.reliability.failure.ConversationFailureDetector;
import com.example.knowledge_system.reliability.failure.EscalationScoreService;
import com.example.knowledge_system.reliability.gate.NeedRetrievalGateService;
import com.example.knowledge_system.reliability.handoff.HandoffService;
import com.example.knowledge_system.reliability.model.ConversationState;
import com.example.knowledge_system.reliability.router.IntentRouterService;
import com.example.knowledge_system.reliability.state.ConversationStateService;
import com.example.knowledge_system.reliability.util.ReliabilityLogUtil;
import com.example.knowledge_system.service.VectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class RagOrchestrator {

    private final ReliabilityProperties properties;
    private final VectorService vectorService;
    private final ConversationStateService stateService;
    private final IntentRouterService intentRouter;
    private final NeedRetrievalGateService retrievalGate;
    private final EvidenceSufficiencyService evidenceService;
    private final ConversationFailureDetector failureDetector;
    private final EscalationScoreService escalationScoreService;
    private final PlannerService plannerService;
    private final HandoffService handoffService;
    private final NoRagHandlerService noRagHandler;

    public RagOrchestrator(ReliabilityProperties properties,
                           VectorService vectorService,
                           ConversationStateService stateService,
                           IntentRouterService intentRouter,
                           NeedRetrievalGateService retrievalGate,
                           EvidenceSufficiencyService evidenceService,
                           ConversationFailureDetector failureDetector,
                           EscalationScoreService escalationScoreService,
                           PlannerService plannerService,
                           HandoffService handoffService,
                           NoRagHandlerService noRagHandler) {
        this.properties = properties;
        this.vectorService = vectorService;
        this.stateService = stateService;
        this.intentRouter = intentRouter;
        this.retrievalGate = retrievalGate;
        this.evidenceService = evidenceService;
        this.failureDetector = failureDetector;
        this.escalationScoreService = escalationScoreService;
        this.plannerService = plannerService;
        this.handoffService = handoffService;
        this.noRagHandler = noRagHandler;
    }

    public AskResult ask(String sessionId, String question) {
        if (!properties.isEnabled()) {
            return vectorService.askWithTools(sessionId, question);
        }

        long totalStart = System.currentTimeMillis();
        List<String> attemptedActions = new ArrayList<>();
        ConversationState state = stateService.load(sessionId);

        RoutingDecision routing = intentRouter.route(question, state);
        ReliabilityLogUtil.logMetric("routingDecision", routing);
        attemptedActions.add("intent_route:" + routing.getSuggestedPath());

        String normalizedQuestion = vectorService.normalizeQuestionForSearch(question);

        FailureAssessment failure = failureDetector.detect(question, state, routing, null);
        int escalationScore = escalationScoreService.updateAndGetScore(
                state, question, routing, failure, null);
        ReliabilityLogUtil.logMetric("escalationScore", escalationScore);

        RetrievalGateResult gate = retrievalGate.evaluate(routing, question);
        ReliabilityLogUtil.logMetric("retrievalGate", gate);

        EvidenceAssessment evidence = null;
        List<DocumentChunkVO> chunks = List.of();

        if (gate.isShouldRetrieve()) {
            String rewritten = vectorService.rewriteQuestionForSession(sessionId, question);
            normalizedQuestion = vectorService.normalizeQuestionForSearch(rewritten);
            attemptedActions.add("rewrite_question");
            chunks = vectorService.search(normalizedQuestion);
            attemptedActions.add("hybrid_search");
            evidence = evidenceService.evaluate(question, chunks);
            ReliabilityLogUtil.logMetric("evidenceAssessment", evidence);

            failure = failureDetector.detect(question, state, routing, evidence);
            escalationScore = escalationScoreService.updateAndGetScore(
                    state, question, routing, failure, evidence);
            ReliabilityLogUtil.logMetric("escalationScore", escalationScore);
        }

        ExecutionPlan plan = plannerService.plan(
                routing, gate, evidence, failure, state, escalationScore);
        ReliabilityLogUtil.logMetric("executionPlan", plan);

        AskResult result = executePlan(sessionId, question, normalizedQuestion, routing, plan,
                failure, evidence, chunks, attemptedActions, state);

        finish(sessionId, question, normalizedQuestion, routing, result, state, totalStart, plan);

        return result;
    }

    public void endSession(String sessionId) {
        stateService.endSession(sessionId);
        vectorService.clearConversationMemory(sessionId);
    }

    private AskResult executePlan(String sessionId,
                                  String question,
                                  String normalizedQuestion,
                                  RoutingDecision routing,
                                  ExecutionPlan plan,
                                  FailureAssessment failure,
                                  EvidenceAssessment evidence,
                                  List<DocumentChunkVO> chunks,
                                  List<String> attemptedActions,
                                  ConversationState state) {
        if (plan.getOutcome() == FinalOutcome.HANDOFF) {
            return handoffService.buildHandoffResponse(
                    question, state, routing, evidence, failure, chunks, attemptedActions);
        }

        if (plan.getOutcome() == FinalOutcome.REFUSE) {
            return refuseResult(plan.getEvidenceStatus(), evidence, chunks);
        }

        return switch (plan.getAnswerMode()) {
            case ADMIN_TOOL -> {
                attemptedActions.add("delegate:executeAdminTool");
                AskResult toolResult = vectorService.executeAdminTool(question);
                if (toolResult != null) {
                    yield toolResult;
                }
                yield AskResult.of("未识别到可执行的管理工具请求，请明确说明文件列表、版本或任务状态。");
            }
            case USER_TOOL -> noRagHandler.handle(sessionId, question, routing);
            case ORDER -> {
                attemptedActions.add("order_deterministic");
                yield vectorService.answerOrderDeterministic(sessionId, question);
            }
            case ORDER_WITH_RAG -> {
                attemptedActions.add("order_with_rag_llm");
                yield vectorService.answerOrderWithPolicy(sessionId, question, chunks);
            }
            case RAG -> vectorService.generateRagAnswer(
                    sessionId, question, normalizedQuestion, chunks, plan.getEvidenceStatus());
            case UNKNOWN -> AskResult.of(vectorService.buildFallbackReply(question));
        };
    }

    private AskResult refuseResult(EvidenceStatus evidenceStatus,
                                   EvidenceAssessment evidence,
                                   List<DocumentChunkVO> chunks) {
        if (evidenceStatus == EvidenceStatus.CONFLICT) {
            return AskResult.of(vectorService.buildConflictReply(chunks, evidence), chunks);
        }
        return AskResult.of(vectorService.buildInsufficientReply(evidence), chunks);
    }

    private void finish(String sessionId,
                        String question,
                        String normalizedQuestion,
                        RoutingDecision routing,
                        AskResult result,
                        ConversationState state,
                        long totalStart,
                        ExecutionPlan plan) {
        String turnLabel = plan.getAnswerMode() + "|" + plan.getEvidenceStatus() + "|" + plan.getOutcome();
        stateService.recordTurn(state, normalizedQuestion, routing.getIntent(), turnLabel);
        stateService.save(sessionId, state);
        vectorService.persistConversationMemory(sessionId, question, result.getAnswer());

        long totalCost = System.currentTimeMillis() - totalStart;
        log.info("METRIC ragTotalCostMs={}, route=RELIABILITY, answerMode={}, evidenceStatus={}, outcome={}, question={}",
                totalCost, plan.getAnswerMode(), plan.getEvidenceStatus(), plan.getOutcome(), question);
    }
}
