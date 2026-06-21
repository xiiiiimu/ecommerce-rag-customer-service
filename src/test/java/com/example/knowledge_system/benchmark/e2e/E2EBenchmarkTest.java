package com.example.knowledge_system.benchmark.e2e;

import com.example.knowledge_system.benchmark.BenchmarkTestApplication;
import com.example.knowledge_system.benchmark.framework.BenchmarkArtifactWriter;
import com.example.knowledge_system.benchmark.framework.GoldenKnowledgeSeeder;
import com.example.knowledge_system.benchmark.support.BenchmarkCacheHelper;
import com.example.knowledge_system.benchmark.support.BenchmarkLogCapture;
import com.example.knowledge_system.benchmark.support.BenchmarkDataSeeder;
import com.example.knowledge_system.benchmark.support.BenchmarkSpringTestConfig;
import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.orchestration.RagOrchestrator;
import com.example.knowledge_system.reliability.model.ConversationState;
import com.example.knowledge_system.reliability.router.IntentRouterService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(classes = BenchmarkTestApplication.class)
@Import(BenchmarkSpringTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class E2EBenchmarkTest {

    @Autowired
    private RagOrchestrator ragOrchestrator;
    @Autowired
    private IntentRouterService intentRouter;
    @Autowired
    private GoldenKnowledgeSeeder goldenSeeder;
    @Autowired
    private BenchmarkDataSeeder dataSeeder;
    @Autowired
    private BenchmarkCacheHelper cacheHelper;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private String sampleOrderNo;

    @BeforeAll
    void setup() throws Exception {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable: " + ex.getMessage());
        }
        goldenSeeder.ensureGoldenKnowledgeLoaded();
        dataSeeder.ensureMinimalOrder();
        sampleOrderNo = dataSeeder.sampleOrderNo();
    }

    @Test
    void runE2EBenchmark() throws Exception {
        cacheHelper.clearRedisAndCaffeine();
        List<E2EQuestionCase> cases = E2EQuestionSuite.standardCases(sampleOrderNo);
        List<Map<String, Object>> rows = new ArrayList<>();

        int intentCorrect = 0;
        int retrievalHit = 0;
        int llmSuccess = 0;
        int fallbackCount = 0;
        int answerAccurate = 0;
        long totalLatency = 0;

        for (E2EQuestionCase testCase : cases) {
            String sessionId = "e2e-" + testCase.category() + "-" + UUID.randomUUID();
            cacheHelper.clearSessionState(sessionId);

            var routing = intentRouter.route(testCase.question(), new ConversationState());
            boolean intentOk = routing.getIntent().contains(testCase.expectedIntent())
                    || ("order".equals(testCase.expectedIntent()) && "order".equals(routing.getIntent()))
                    || ("knowledge_query".equals(testCase.expectedIntent()) && "knowledge_query".equals(routing.getIntent()));
            if (intentOk) {
                intentCorrect++;
            }

            try (BenchmarkLogCapture capture = new BenchmarkLogCapture()) {
                long start = System.currentTimeMillis();
                AskResult result = ragOrchestrator.ask(sessionId, testCase.question());
                long latency = System.currentTimeMillis() - start;
                totalLatency += latency;

                String answer = result.getAnswer() == null ? "" : result.getAnswer();
                boolean fallback = isFallback(answer);
                if (fallback) {
                    fallbackCount++;
                }
                boolean llmOk = !answer.isBlank() && !fallback;
                if (llmOk) {
                    llmSuccess++;
                }
                boolean retrievalOk = testCase.expectedFile() == null
                        || result.getChunks().stream().anyMatch(c ->
                        GoldenKnowledgeSeeder.matchesExpectedFile(c.getFileName(), testCase.expectedFile()));
                if (retrievalOk) {
                    retrievalHit++;
                }
                boolean answerOk = containsAnyKeyword(answer, testCase.expectedKeywords());
                if (answerOk) {
                    answerAccurate++;
                }

                rows.add(Map.of(
                        "category", testCase.category(),
                        "question", testCase.question(),
                        "intent", routing.getIntent(),
                        "intentCorrect", intentOk,
                        "retrievalHit", retrievalOk,
                        "llmSuccess", llmOk,
                        "fallback", fallback,
                        "answerAccurate", answerOk,
                        "latencyMs", latency,
                        "answerPreview", answer.length() > 120 ? answer.substring(0, 120) : answer
                ));
                ragOrchestrator.endSession(sessionId);
            }
        }

        Map<String, Object> multiTurn = runMultiTurn();
        int caseCount = cases.size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", caseCount);
        summary.put("intentRouterAccuracy", rate(intentCorrect, caseCount));
        summary.put("retrievalRecall", rate(retrievalHit, caseCount));
        summary.put("llmResponseSuccessRate", rate(llmSuccess, caseCount));
        summary.put("fallbackTriggerRate", rate(fallbackCount, caseCount));
        summary.put("answerAccuracy", rate(answerAccurate, caseCount));
        summary.put("avgEndToEndLatencyMs", caseCount == 0 ? 0 : (double) totalLatency / caseCount);
        summary.put("multiTurn", multiTurn);

        Map<String, Object> payload = BenchmarkArtifactWriter.resultEnvelope("e2e", Map.of(
                "summary", summary,
                "cases", rows
        ));
        BenchmarkArtifactWriter.writeJson("e2e_results.json", payload);

        StringBuilder md = new StringBuilder(BenchmarkArtifactWriter.header("End-to-End Benchmark"));
        md.append(BenchmarkArtifactWriter.metricsTable(summary));
        md.append("## Case Details\n\n");
        md.append("| Category | Intent OK | Retrieval | LLM OK | Fallback | Answer OK | Latency(ms) |\n");
        md.append("|---|---|---|---|---|---|---:|\n");
        for (Map<String, Object> row : rows) {
            md.append("| ").append(row.get("category")).append(" | ")
                    .append(row.get("intentCorrect")).append(" | ")
                    .append(row.get("retrievalHit")).append(" | ")
                    .append(row.get("llmSuccess")).append(" | ")
                    .append(row.get("fallback")).append(" | ")
                    .append(row.get("answerAccurate")).append(" | ")
                    .append(row.get("latencyMs")).append(" |\n");
        }
        BenchmarkArtifactWriter.writeMarkdown("e2e_benchmark.md", md.toString());
    }

    private Map<String, Object> runMultiTurn() {
        String sessionId = "e2e-multiturn-" + UUID.randomUUID();
        cacheHelper.clearSessionState(sessionId);
        long start = System.currentTimeMillis();
        AskResult last = null;
        for (String turn : E2EQuestionSuite.multiTurnScript(sampleOrderNo)) {
            last = ragOrchestrator.ask(sessionId, turn);
        }
        long latency = System.currentTimeMillis() - start;
        ragOrchestrator.endSession(sessionId);
        String answer = last == null ? "" : last.getAnswer();
        return Map.of(
                "turns", E2EQuestionSuite.multiTurnScript(sampleOrderNo).size(),
                "latencyMs", latency,
                "success", !answer.isBlank() && !isFallback(answer),
                "answerPreview", answer.length() > 120 ? answer.substring(0, 120) : answer
        );
    }

    private boolean isFallback(String answer) {
        return answer.contains("暂无足够依据")
                || answer.contains("大模型服务暂时不可用")
                || answer.contains("转接人工");
    }

    private boolean containsAnyKeyword(String answer, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return !answer.isBlank();
        }
        for (String part : keywords.split(";")) {
            if (!part.isBlank() && answer.contains(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private double rate(int hits, int total) {
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
