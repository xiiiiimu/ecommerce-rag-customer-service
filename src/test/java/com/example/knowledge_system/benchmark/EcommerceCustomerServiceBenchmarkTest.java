package com.example.knowledge_system.benchmark;

import com.example.knowledge_system.benchmark.model.BenchmarkReport;
import com.example.knowledge_system.benchmark.support.BenchmarkCacheHelper;
import com.example.knowledge_system.benchmark.support.BenchmarkDataSeeder;
import com.example.knowledge_system.benchmark.support.BenchmarkLogCapture;
import com.example.knowledge_system.benchmark.support.BenchmarkReportWriter;
import com.example.knowledge_system.benchmark.support.BenchmarkSpringTestConfig;
import com.example.knowledge_system.dto.AskResult;
import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.orchestration.RagOrchestrator;
import com.example.knowledge_system.service.Bm25SearchService;
import com.example.knowledge_system.service.VectorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

@SpringBootTest(classes = BenchmarkTestApplication.class)
@Import(BenchmarkSpringTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcommerceCustomerServiceBenchmarkTest {

    private static final List<ScenarioQuery> GROUP_A_QUERIES = List.of(
            new ScenarioQuery("FAQ", "优惠券通常有什么限制", answer -> !answer.isBlank()),
            new ScenarioQuery("Order", "查询订单 ORD2026BENCH000001", answer -> answer.contains("订单")),
            new ScenarioQuery("Refund", "退款申请多久审核", answer -> !answer.isBlank()),
            new ScenarioQuery("Logistics", "物流信息多久更新", answer -> !answer.isBlank()),
            new ScenarioQuery("Knowledge", "双11满减规则是什么", answer -> !answer.isBlank())
    );

    private static final int RECALL_SAMPLE_SIZE = 30;

    private final BenchmarkReport report = new BenchmarkReport();

    @Autowired
    private RagOrchestrator ragOrchestrator;

    @Autowired
    private VectorService vectorService;

    @Autowired
    private BenchmarkCacheHelper cacheHelper;

    @Autowired
    private BenchmarkDataSeeder dataSeeder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private Bm25SearchService bm25SearchService;

    @SpyBean
    private EmbeddingModel embeddingModel;

    @SpyBean
    private ChatClient chatClient;

    @Autowired
    private DocumentChunkVectorMapper documentChunkVectorMapper;

    @BeforeAll
    void checkInfrastructureAndSeedBaseline() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
        } catch (Exception ex) {
            Assumptions.abort("Redis unavailable: " + ex.getMessage());
        }
        dataSeeder.ensureMinimalOrder();
        report.setEnvironmentNote("Benchmark requires PostgreSQL, PGVector, Elasticsearch, Redis, Ollama, DeepSeek.");
    }

    @Test
    @Order(1)
    void groupA_coldStart() throws Exception {
        cacheHelper.clearRedisAndCaffeine();
        BenchmarkReport.GroupMetrics metrics = runScenarioSet("cold-start", GROUP_A_QUERIES, true);
        metrics.setLabel("Group A - Cold Start");
        report.setGroupA(metrics);
    }

    @Test
    @Order(2)
    void groupB_warmCache() throws Exception {
        BenchmarkReport.GroupBMetrics groupB = new BenchmarkReport.GroupBMetrics();
        groupB.setWarmupRun(runScenarioSet("warmup", GROUP_A_QUERIES, false));
        groupB.setWarmRun(runScenarioSet("warm", GROUP_A_QUERIES, false));

        BenchmarkReport.GroupMetrics cold = report.getGroupA();
        if (cold != null) {
            Map<String, Double> coldByCategory = new LinkedHashMap<>();
            Map<String, Double> warmByCategory = new LinkedHashMap<>();
            for (BenchmarkReport.QueryResult query : cold.getQueries()) {
                coldByCategory.put(query.getCategory(), (double) query.getResponseTimeMs());
            }
            for (BenchmarkReport.QueryResult query : groupB.getWarmRun().getQueries()) {
                warmByCategory.put(query.getCategory(), (double) query.getResponseTimeMs());
            }
            groupB.setColdResponseByCategory(coldByCategory);
            groupB.setWarmResponseByCategory(warmByCategory);

            double coldAvg = cold.getAvgResponseTimeMs();
            double warmAvg = groupB.getWarmRun().getAvgResponseTimeMs();
            groupB.setLatencyImprovementPct(coldAvg <= 0 ? 0 : ((coldAvg - warmAvg) / coldAvg) * 100.0);
        }
        report.setGroupB(groupB);
    }

    @Test
    @Order(3)
    void groupC_existingData() throws Exception {
        Map<String, Object> seedStats = dataSeeder.ensureBenchmarkDataset();
        List<ScenarioQuery> groupCQueries = List.of(
                new ScenarioQuery("FAQ Query", "Benchmark FAQ问题50是什么？", answer -> answer.contains("FAQ") || !answer.isBlank()),
                new ScenarioQuery("Product Query", "Benchmark商品120的发货规则是什么", answer -> !answer.isBlank()),
                new ScenarioQuery("Order Query", "查询订单 " + dataSeeder.sampleOrderNo(), answer -> answer.contains("订单")),
                new ScenarioQuery("Return Query", "7天无理由退货要求是什么", answer -> !answer.isBlank()),
                new ScenarioQuery("Complex Multi-turn", "multi-turn", answer -> !answer.isBlank())
        );

        cacheHelper.clearRedisAndCaffeine();
        BenchmarkReport.GroupCMetrics groupC = new BenchmarkReport.GroupCMetrics();
        groupC.setSeedStats(seedStats);

        List<BenchmarkReport.QueryResult> queryResults = new ArrayList<>();
        for (ScenarioQuery scenario : groupCQueries) {
            if ("Complex Multi-turn".equals(scenario.category())) {
                queryResults.add(runMultiTurnScenario());
            } else {
                queryResults.add(runSingleQuery(scenario, "groupC-" + scenario.category(), true));
            }
        }
        groupC.setQueries(queryResults);

        RecallEvaluation recall = evaluateRetrievalRecall();
        groupC.setRecallSampleSize(recall.sampleSize());
        groupC.setRecallHits(recall.hits());
        groupC.setRetrievalRecall(recall.recall());

        groupC.setAvgRetrievalLatencyMs(average(queryResults, BenchmarkReport.QueryResult::getRetrievalTimeMs));
        groupC.setAvgLlmLatencyMs(average(queryResults, BenchmarkReport.QueryResult::getLlmTimeMs));
        groupC.setAvgEndToEndLatencyMs(average(queryResults, BenchmarkReport.QueryResult::getResponseTimeMs));

        report.setGroupC(groupC);
    }

    @Test
    @Order(4)
    void reliabilityTests() throws Exception {
        reset(embeddingModel, chatClient);

        report.getReliability().add(runReliabilityScenario(
                "Ollama Down",
                () -> doThrow(new RuntimeException("Ollama unavailable")).when(embeddingModel).embed(anyString()),
                "物流信息多久更新",
                answer -> answer.contains("大模型服务暂时不可用")
                        || answer.contains("知识库")
                        || answer.contains("检索"),
                true,
                false
        ));

        reset(embeddingModel, chatClient);
        report.getReliability().add(runReliabilityScenario(
                "DeepSeek Timeout",
                this::stubDeepSeekTimeout,
                "双11满减规则是什么",
                answer -> answer.contains("大模型服务暂时不可用")
                        || answer.contains("检索结果摘要")
                        || answer.contains("暂无足够依据"),
                false,
                true
        ));

        reset(embeddingModel, chatClient);
        report.getReliability().add(runElasticsearchDownScenario());

        reset(embeddingModel, chatClient);
        report.getReliability().add(runPgVectorFailureScenario());

        reset(embeddingModel, chatClient);
        report.getReliability().add(runHandoffScenario());
    }

    private BenchmarkReport.ReliabilityResult runPgVectorFailureScenario() throws Exception {
        DocumentChunkVectorMapper failingSpy = spy(documentChunkVectorMapper);
        doThrow(new RuntimeException("PGVector unavailable")).when(failingSpy).searchSimilar(anyString());
        ReflectionTestUtils.setField(vectorService, "mapper", failingSpy);
        try {
            return runReliabilityScenario(
                    "PGVector Query Failure",
                    () -> { },
                    "退款申请多久审核",
                    answer -> !answer.isBlank(),
                    true,
                    false
            );
        } finally {
            ReflectionTestUtils.setField(vectorService, "mapper", documentChunkVectorMapper);
        }
    }

    private BenchmarkReport.ReliabilityResult runElasticsearchDownScenario() throws Exception {
        Bm25SearchService failingSpy = spy(bm25SearchService);
        doThrow(new RuntimeException("Elasticsearch unavailable")).when(failingSpy).search(anyString(), anyInt());
        ReflectionTestUtils.setField(vectorService, "bm25SearchService", failingSpy);
        try {
            return runReliabilityScenario(
                    "Elasticsearch Down",
                    () -> { },
                    "优惠券通常有什么限制",
                    answer -> !answer.isBlank(),
                    true,
                    false
            );
        } finally {
            ReflectionTestUtils.setField(vectorService, "bm25SearchService", bm25SearchService);
        }
    }

    @AfterAll
    void writeBenchmarkArtifacts() throws Exception {
        BenchmarkReportWriter.write(report);
    }

    private BenchmarkReport.GroupMetrics runScenarioSet(String runLabel,
                                                        List<ScenarioQuery> scenarios,
                                                        boolean clearCacheBeforeEach) throws Exception {
        BenchmarkReport.GroupMetrics group = new BenchmarkReport.GroupMetrics();
        group.setLabel(runLabel);
        List<BenchmarkReport.QueryResult> results = new ArrayList<>();
        for (ScenarioQuery scenario : scenarios) {
            if (clearCacheBeforeEach) {
                cacheHelper.clearRedisAndCaffeine();
            }
            results.add(runSingleQuery(scenario, runLabel + "-" + scenario.category(), clearCacheBeforeEach));
        }
        group.setQueries(results);
        group.setAvgResponseTimeMs(average(results, BenchmarkReport.QueryResult::getResponseTimeMs));
        group.setAvgRetrievalTimeMs(average(results, BenchmarkReport.QueryResult::getRetrievalTimeMs));
        group.setAvgLlmTimeMs(average(results, BenchmarkReport.QueryResult::getLlmTimeMs));
        group.setCacheHitRate(averageDouble(results, query -> {
            int total = query.getCacheHits() + query.getCacheMisses();
            return total == 0 ? 0.0 : (double) query.getCacheHits() / total;
        }));
        return group;
    }

    private BenchmarkReport.QueryResult runSingleQuery(ScenarioQuery scenario,
                                                       String sessionPrefix,
                                                       boolean clearSession) throws Exception {
        String sessionId = sessionPrefix + "-" + UUID.randomUUID();
        if (clearSession) {
            cacheHelper.clearSessionState(sessionId);
        }

        BenchmarkLogCapture capture = new BenchmarkLogCapture();
        try {
            capture.reset();
            long start = System.currentTimeMillis();
            AskResult result = ragOrchestrator.ask(sessionId, scenario.question());
            long elapsed = System.currentTimeMillis() - start;
            BenchmarkLogCapture.CapturedMetrics metrics = capture.snapshot();

            BenchmarkReport.QueryResult queryResult = new BenchmarkReport.QueryResult();
            queryResult.setCategory(scenario.category());
            queryResult.setQuestion(scenario.question());
            queryResult.setSessionId(sessionId);
            queryResult.setResponseTimeMs(elapsed);
            queryResult.setRetrievalTimeMs(resolveRetrievalTime(metrics, elapsed));
            queryResult.setLlmTimeMs(metrics.getLlmCostMs());
            queryResult.setCacheHits(metrics.totalCacheHits());
            queryResult.setCacheMisses(metrics.totalCacheMisses());
            queryResult.setChunkCount(result.getChunks() == null ? 0 : result.getChunks().size());
            queryResult.setAnswerPreview(preview(result.getAnswer()));
            queryResult.setSuccess(scenario.successCheck().test(result.getAnswer()));
            ragOrchestrator.endSession(sessionId);
            return queryResult;
        } finally {
            capture.close();
        }
    }

    private BenchmarkReport.QueryResult runMultiTurnScenario() throws Exception {
        String sessionId = "groupC-multiturn-" + UUID.randomUUID();
        cacheHelper.clearSessionState(sessionId);

        List<String> turns = List.of(
                "我想了解订单 " + dataSeeder.sampleOrderNo() + " 的物流情况",
                "如果这个订单想退款，需要满足什么条件？",
                "退款一般多久到账？"
        );

        BenchmarkLogCapture capture = new BenchmarkLogCapture();
        try {
            capture.reset();
            long start = System.currentTimeMillis();
            AskResult lastResult = null;
            for (String turn : turns) {
                lastResult = ragOrchestrator.ask(sessionId, turn);
            }
            long elapsed = System.currentTimeMillis() - start;
            BenchmarkLogCapture.CapturedMetrics metrics = capture.snapshot();

            BenchmarkReport.QueryResult queryResult = new BenchmarkReport.QueryResult();
            queryResult.setCategory("Complex Multi-turn");
            queryResult.setQuestion(String.join(" -> ", turns));
            queryResult.setSessionId(sessionId);
            queryResult.setResponseTimeMs(elapsed);
            queryResult.setRetrievalTimeMs(resolveRetrievalTime(metrics, elapsed));
            queryResult.setLlmTimeMs(metrics.getLlmCostMs());
            queryResult.setCacheHits(metrics.totalCacheHits());
            queryResult.setCacheMisses(metrics.totalCacheMisses());
            queryResult.setChunkCount(lastResult == null || lastResult.getChunks() == null ? 0 : lastResult.getChunks().size());
            queryResult.setAnswerPreview(lastResult == null ? "" : preview(lastResult.getAnswer()));
            queryResult.setSuccess(lastResult != null && !lastResult.getAnswer().isBlank());
            ragOrchestrator.endSession(sessionId);
            return queryResult;
        } finally {
            capture.close();
        }
    }

    private BenchmarkReport.ReliabilityResult runReliabilityScenario(String scenario,
                                                                     Runnable stubbing,
                                                                     String question,
                                                                     Predicate<String> fallbackCheck,
                                                                     boolean expectDegrade,
                                                                     boolean expectLlmFallback) throws Exception {
        stubbing.run();
        cacheHelper.clearRedisAndCaffeine();
        String sessionId = "reliability-" + scenario.replace(' ', '-') + "-" + UUID.randomUUID();

        BenchmarkLogCapture capture = new BenchmarkLogCapture();
        BenchmarkReport.ReliabilityResult result = new BenchmarkReport.ReliabilityResult();
        result.setScenario(scenario);
        try {
            capture.reset();
            AskResult askResult = ragOrchestrator.ask(sessionId, question);
            BenchmarkLogCapture.CapturedMetrics metrics = capture.snapshot();

            result.setAnswerPreview(preview(askResult.getAnswer()));
            result.setDegradationTriggered(!metrics.getRetrievalDegrades().isEmpty() || metrics.isLlmUnavailable());
            result.setHandoffTriggered(askResult.getAnswer().contains("转接人工"));
            result.setFallbackAnswerReturned(fallbackCheck.test(askResult.getAnswer()));
            result.getObservedMetrics().addAll(buildObservedMetrics(metrics));
            result.setPassed((!expectDegrade || result.isDegradationTriggered())
                    && (!expectLlmFallback || result.isFallbackAnswerReturned())
                    && !askResult.getAnswer().isBlank());
            ragOrchestrator.endSession(sessionId);
            return result;
        } finally {
            capture.close();
        }
    }

    private BenchmarkReport.ReliabilityResult runHandoffScenario() throws Exception {
        cacheHelper.clearRedisAndCaffeine();
        String sessionId = "reliability-handoff-" + UUID.randomUUID();
        AskResult askResult = ragOrchestrator.ask(sessionId, "我要投诉并要求赔偿，请转人工处理");

        BenchmarkReport.ReliabilityResult result = new BenchmarkReport.ReliabilityResult();
        result.setScenario("Handoff Trigger");
        result.setAnswerPreview(preview(askResult.getAnswer()));
        result.setHandoffTriggered(askResult.getAnswer().contains("转接人工"));
        result.setFallbackAnswerReturned(false);
        result.setDegradationTriggered(false);
        result.getObservedMetrics().add("handoff=" + result.isHandoffTriggered());
        result.setPassed(result.isHandoffTriggered());
        ragOrchestrator.endSession(sessionId);
        return result;
    }

    private void stubDeepSeekTimeout() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        doReturn(requestSpec).when(chatClient).prompt();
        doReturn(requestSpec).when(requestSpec).user(anyString());
        doReturn(responseSpec).when(requestSpec).call();
        doThrow(new ResourceAccessException("DeepSeek timeout")).when(responseSpec).content();
    }

    private RecallEvaluation evaluateRetrievalRecall() throws IOException {
        Path csv = Path.of("rag_eval_questions.csv");
        Assumptions.assumeTrue(Files.exists(csv), "rag_eval_questions.csv not found");

        List<EvalCase> cases = loadEvalCases(csv);
        int sampleSize = Math.min(RECALL_SAMPLE_SIZE, cases.size());
        int hits = 0;

        cacheHelper.clearRedisAndCaffeine();
        for (int i = 0; i < sampleSize; i++) {
            EvalCase evalCase = cases.get(i);
            try {
                List<DocumentChunkVO> chunks = vectorService.search(evalCase.question());
                if (matchesExpectedFile(chunks, evalCase.expectedFile())) {
                    hits++;
                }
            } catch (Exception ignored) {
                // e.g. mixed embedding dimensions in pgvector
            }
        }
        double recall = sampleSize == 0 ? 0.0 : (double) hits / sampleSize;
        return new RecallEvaluation(sampleSize, hits, recall);
    }

    private List<EvalCase> loadEvalCases(Path csv) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", 3);
                if (parts.length < 2) {
                    continue;
                }
                cases.add(new EvalCase(parts[0].trim(), parts[1].trim()));
            }
        }
        return cases;
    }

    private boolean matchesExpectedFile(List<DocumentChunkVO> chunks, String expectedFile) {
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        for (DocumentChunkVO chunk : chunks) {
            String fileName = chunk.getFileName();
            if (fileName == null) {
                continue;
            }
            if (fileName.equals(expectedFile) || fileName.endsWith(expectedFile)) {
                return true;
            }
        }
        return false;
    }

    private long resolveRetrievalTime(BenchmarkLogCapture.CapturedMetrics metrics, long elapsed) {
        if (metrics.getSearchCostMs() > 0) {
            return metrics.getSearchCostMs();
        }
        long remainder = elapsed - metrics.getLlmCostMs();
        return Math.max(remainder, 0);
    }

    private List<String> buildObservedMetrics(BenchmarkLogCapture.CapturedMetrics metrics) {
        List<String> observed = new ArrayList<>();
        if (!metrics.getRetrievalDegrades().isEmpty()) {
            observed.add("retrievalDegrade=" + String.join("|", metrics.getRetrievalDegrades()));
        }
        if (metrics.isLlmUnavailable()) {
            observed.add("llmUnavailable=1");
        }
        observed.add("searchCostMs=" + metrics.getSearchCostMs());
        observed.add("llmCostMs=" + metrics.getLlmCostMs());
        return observed;
    }

    private double average(List<BenchmarkReport.QueryResult> results,
                           java.util.function.ToLongFunction<BenchmarkReport.QueryResult> extractor) {
        if (results.isEmpty()) {
            return 0.0;
        }
        long sum = 0;
        for (BenchmarkReport.QueryResult result : results) {
            sum += extractor.applyAsLong(result);
        }
        return (double) sum / results.size();
    }

    private double averageDouble(List<BenchmarkReport.QueryResult> results,
                                 java.util.function.ToDoubleFunction<BenchmarkReport.QueryResult> extractor) {
        if (results.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (BenchmarkReport.QueryResult result : results) {
            sum += extractor.applyAsDouble(result);
        }
        return sum / results.size();
    }

    private String preview(String answer) {
        if (answer == null) {
            return "";
        }
        String normalized = answer.replace('\n', ' ').trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private record ScenarioQuery(String category, String question, Predicate<String> successCheck) {
    }

    private record EvalCase(String question, String expectedFile) {
    }

    private record RecallEvaluation(int sampleSize, int hits, double recall) {
    }
}
