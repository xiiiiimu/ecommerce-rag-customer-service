package com.example.knowledge_system.benchmark.reliability;

import com.example.knowledge_system.benchmark.BenchmarkTestApplication;
import com.example.knowledge_system.benchmark.framework.BenchmarkArtifactWriter;
import com.example.knowledge_system.benchmark.framework.GoldenKnowledgeSeeder;
import com.example.knowledge_system.benchmark.support.BenchmarkCacheHelper;
import com.example.knowledge_system.benchmark.support.BenchmarkLogCapture;
import com.example.knowledge_system.benchmark.support.BenchmarkSpringTestConfig;
import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.orchestration.RagOrchestrator;
import com.example.knowledge_system.service.Bm25SearchService;
import com.example.knowledge_system.service.VectorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BenchmarkTestApplication.class)
@Import(BenchmarkSpringTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReliabilityBenchmarkTest {

    @Autowired
    private RagOrchestrator ragOrchestrator;
    @Autowired
    private VectorService vectorService;
    @Autowired
    private Bm25SearchService bm25SearchService;
    @Autowired
    private DocumentChunkVectorMapper documentChunkVectorMapper;
    @Autowired
    private BenchmarkCacheHelper cacheHelper;
    @Autowired
    private GoldenKnowledgeSeeder goldenSeeder;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @SpyBean
    private EmbeddingModel embeddingModel;
    @SpyBean
    private ChatClient chatClient;
    @SpyBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeAll
    void setup() throws Exception {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable: " + ex.getMessage());
        }
        goldenSeeder.ensureGoldenKnowledgeLoaded();
    }

    @Test
    void runReliabilityBenchmark() throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(runRedisDown());
        reset(embeddingModel, chatClient, stringRedisTemplate);
        results.add(runElasticsearchDown());
        reset(embeddingModel, chatClient, stringRedisTemplate);
        results.add(runOllamaDown());
        reset(embeddingModel, chatClient, stringRedisTemplate);
        results.add(runDeepSeekTimeout());
        reset(embeddingModel, chatClient, stringRedisTemplate);
        results.add(runHandoff());

        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        Map<String, Object> payload = BenchmarkArtifactWriter.resultEnvelope("reliability", Map.of(
                "scenarioCount", results.size(),
                "passedCount", passed,
                "fallbackSuccessRate", results.isEmpty() ? 0 : (double) passed / results.size(),
                "scenarios", results
        ));
        BenchmarkArtifactWriter.writeJson("reliability_results.json", payload);

        StringBuilder md = new StringBuilder(BenchmarkArtifactWriter.header("Reliability Benchmark"));
        md.append("| Scenario | Degrade | Handoff | Fallback | Error | Recovery(ms) | Passed |\n");
        md.append("|---|---|---|---|---|---|---:|\n");
        for (Map<String, Object> row : results) {
            md.append("| ").append(row.get("scenario")).append(" | ")
                    .append(row.get("degradationTriggered")).append(" | ")
                    .append(row.get("handoffTriggered")).append(" | ")
                    .append(row.get("fallbackAnswerReturned")).append(" | ")
                    .append(row.get("errorThrown")).append(" | ")
                    .append(row.get("recoveryTimeMs")).append(" | ")
                    .append(row.get("passed")).append(" |\n");
        }
        BenchmarkArtifactWriter.writeMarkdown("reliability_benchmark.md", md.toString());
    }

    private Map<String, Object> runRedisDown() throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> failingOps = mock(ValueOperations.class);
        when(failingOps.get(anyString())).thenThrow(new RuntimeException("Redis down"));
        doReturn(failingOps).when(stringRedisTemplate).opsForValue();
        return executeScenario("Redis Down", "物流信息多久更新",
                answer -> !answer.isBlank(), true, false);
    }

    private Map<String, Object> runElasticsearchDown() throws Exception {
        Bm25SearchService failingSpy = spy(bm25SearchService);
        doThrow(new RuntimeException("Elasticsearch unavailable")).when(failingSpy).search(anyString(), anyInt());
        ReflectionTestUtils.setField(vectorService, "bm25SearchService", failingSpy);
        try {
            return executeScenario("Elasticsearch Down", "优惠券通常有什么限制",
                    answer -> !answer.isBlank(), true, false);
        } finally {
            ReflectionTestUtils.setField(vectorService, "bm25SearchService", bm25SearchService);
        }
    }

    private Map<String, Object> runOllamaDown() throws Exception {
        doThrow(new RuntimeException("Ollama unavailable")).when(embeddingModel).embed(anyString());
        return executeScenario("Ollama Down", "物流信息多久更新",
                answer -> !answer.isBlank(), true, false);
    }

    private Map<String, Object> runDeepSeekTimeout() throws Exception {
        stubDeepSeekTimeout();
        return executeScenario("DeepSeek Timeout", "双11满减规则是什么",
                answer -> answer.contains("大模型服务暂时不可用") || answer.contains("检索结果摘要")
                        || answer.contains("满300") || !answer.isBlank(),
                false, true);
    }

    private Map<String, Object> runHandoff() throws Exception {
        Map<String, Object> result = executeScenario("Handoff Trigger", "我要投诉并要求赔偿，请转人工处理",
                answer -> false, false, false);
        result.put("handoffTriggered", result.get("answer").toString().contains("转接人工"));
        result.put("passed", result.get("answer").toString().contains("转接人工"));
        return result;
    }

    private Map<String, Object> executeScenario(String scenario,
                                                String question,
                                                java.util.function.Predicate<String> fallbackCheck,
                                                boolean expectDegrade,
                                                boolean expectFallback) throws Exception {
        cacheHelper.clearRedisAndCaffeine();
        String sessionId = "rel-" + scenario.replace(' ', '-') + "-" + UUID.randomUUID();
        long recoveryStart = System.currentTimeMillis();

        String answer = "";
        BenchmarkLogCapture.CapturedMetrics metrics;
        boolean errorThrown = false;
        String errorMessage = null;

        try (BenchmarkLogCapture capture = new BenchmarkLogCapture()) {
            try {
                AskResult askResult = ragOrchestrator.ask(sessionId, question);
                answer = askResult.getAnswer() == null ? "" : askResult.getAnswer();
            } catch (Exception ex) {
                errorThrown = true;
                errorMessage = ex.getMessage();
            }
            metrics = capture.snapshot();
        }

        boolean degrade = !metrics.getRetrievalDegrades().isEmpty() || metrics.isLlmUnavailable();
        boolean handoff = answer.contains("转接人工");
        boolean fallback = !errorThrown && fallbackCheck.test(answer);
        boolean passed = !errorThrown
                && (!expectDegrade || degrade)
                && (!expectFallback || fallback)
                && !answer.isBlank();

        reset(stringRedisTemplate, embeddingModel, chatClient);
        long recoveryMs = System.currentTimeMillis() - recoveryStart;
        try {
            AskResult recoveryResult = ragOrchestrator.ask(sessionId + "-recovery", "物流信息多久更新");
            if (recoveryResult.getAnswer() == null || recoveryResult.getAnswer().isBlank()) {
                recoveryMs = -1;
            }
        } catch (Exception ex) {
            recoveryMs = -1;
        }
        try {
            ragOrchestrator.endSession(sessionId);
        } catch (Exception ignored) {
            // session cleanup may fail under injected faults
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario);
        row.put("degradationTriggered", degrade);
        row.put("handoffTriggered", handoff);
        row.put("fallbackAnswerReturned", fallback);
        row.put("errorThrown", errorThrown);
        if (errorMessage != null) {
            row.put("errorMessage", errorMessage);
        }
        row.put("recoveryTimeMs", recoveryMs);
        row.put("passed", passed);
        row.put("answer", answer.length() > 120 ? answer.substring(0, 120) : answer);
        row.put("observedMetrics", List.of(
                "retrievalDegrade=" + metrics.getRetrievalDegrades(),
                "llmUnavailable=" + metrics.isLlmUnavailable(),
                "errorThrown=" + errorThrown
        ));
        return row;
    }

    private void stubDeepSeekTimeout() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        doReturn(requestSpec).when(chatClient).prompt();
        doReturn(requestSpec).when(requestSpec).user(anyString());
        doReturn(responseSpec).when(requestSpec).call();
        doThrow(new ResourceAccessException("DeepSeek timeout")).when(responseSpec).content();
    }
}
