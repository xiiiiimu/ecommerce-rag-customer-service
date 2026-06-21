package com.example.knowledge_system.service;

import com.example.knowledge_system.dto.*;
import com.example.knowledge_system.reliability.dto.EvidenceStatus;
import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import com.example.knowledge_system.entity.CustomerOrder;
import com.example.knowledge_system.entity.DocumentChunkVector;
import com.example.knowledge_system.mapper.CustomerOrderMapper;
import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorService {

    private final DocumentChunkVectorMapper mapper;
    private final EmbeddingService embeddingService;
    private final ChatClient chatClient;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final CustomerOrderMapper customerOrderMapper;
    private final UploadTaskService uploadTaskService;
    private final Bm25SearchService bm25SearchService;

    // Caffeine 本地一级缓存
    private final Cache<String, String> ragEmbeddingCache;
    private final Cache<String, String> ragAnswerCache;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String KB_MARKER_KEY = "rag:kb:marker";
    private static final String HOT_COUNTER_PREFIX = "rag:hot:counter:";
    private static final String REDIS_EMBED_PREFIX = "rag:embed:v1:";
    private static final String REDIS_ANSWER_PREFIX = "rag:answer:";
    private static final String REDIS_SEARCH_PREFIX = "rag:search:";
    private static final String PROMPT_VERSION = "v1";
    private static final double BM25_SCORE_THRESHOLD = 0.1;

    private static final Duration EMBEDDING_TTL = Duration.ofDays(7);
    private static final Duration ANSWER_TTL = Duration.ofMinutes(30);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(10);
    private static final Duration HOT_COUNTER_TTL = Duration.ofHours(1);
    private static final long HOT_THRESHOLD = 5L;
    private static final double VECTOR_SCORE_THRESHOLD = 0.30;
    public VectorService(DocumentChunkVectorMapper mapper,
                         EmbeddingService embeddingService,
                         ChatClient chatClient,
                         RedissonClient redissonClient,
                         StringRedisTemplate stringRedisTemplate,
                         CustomerOrderMapper customerOrderMapper,
                         UploadTaskService uploadTaskService,
                         Bm25SearchService bm25SearchService,
                         @Qualifier("ragEmbeddingCache") Cache<String, String> ragEmbeddingCache,
                         @Qualifier("ragAnswerCache") Cache<String, String> ragAnswerCache) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.chatClient = chatClient;
        this.redissonClient = redissonClient;
        this.stringRedisTemplate = stringRedisTemplate;
        this.customerOrderMapper = customerOrderMapper;
        this.uploadTaskService = uploadTaskService;
        this.ragEmbeddingCache = ragEmbeddingCache;
        this.ragAnswerCache = ragAnswerCache;
        this.bm25SearchService = bm25SearchService;
    }

    // =========================
    // RAG 缓存核心
    // =========================

    private String normalizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        return question.trim().replaceAll("\\s+", " ");
    }

    private String md5(String text) {
        return DigestUtils.md5DigestAsHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private String getKnowledgeMarker() {
        String marker = stringRedisTemplate.opsForValue().get(KB_MARKER_KEY);
        if (marker == null || marker.isBlank()) {
            marker = "init";
            stringRedisTemplate.opsForValue().set(KB_MARKER_KEY, marker);
            log.info("kb marker init: {}", marker);
        } else {
            log.info("kb marker current: {}", marker);
        }
        return marker;
    }

    private void clearAnswerCache() {
        Set<String> keys = stringRedisTemplate.keys(REDIS_ANSWER_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("answer cache cleared, size={}", keys.size());
        }

        ragAnswerCache.invalidateAll();
        log.info("local answer cache cleared");
    }

    private void touchKnowledgeMarker() {
        String marker = String.valueOf(System.currentTimeMillis());
        stringRedisTemplate.opsForValue().set(KB_MARKER_KEY, marker);

        clearSearchCache();
        clearAnswerCache();

        log.info("kb marker updated: {}", marker);
    }

    private String buildEmbeddingKey(String question) {
        return REDIS_EMBED_PREFIX + md5(question);
    }

    private String buildAnswerKey(String question, String kbMarker) {
        return REDIS_ANSWER_PREFIX + kbMarker + ":" + PROMPT_VERSION + ":" + md5(question);
    }

    private String buildSearchKey(String question, String kbMarker) {
        return REDIS_SEARCH_PREFIX + kbMarker + ":" + md5(question);
    }

    private boolean isHotQuestion(String normalizedQuestion) {
        String counterKey = HOT_COUNTER_PREFIX + md5(normalizedQuestion);
        Long count = stringRedisTemplate.opsForValue().increment(counterKey);
        stringRedisTemplate.expire(counterKey, HOT_COUNTER_TTL);

        boolean hot = count != null && count >= HOT_THRESHOLD;
        log.info("hot question check: question={}, count={}, hot={}", normalizedQuestion, count, hot);
        return hot;
    }

    /**
     * 两级 embedding 缓存：Caffeine -> Redis -> EmbeddingService
     */
    private String getOrLoadEmbedding(String normalizedQuestion) {
        String key = buildEmbeddingKey(normalizedQuestion);

        String localValue = ragEmbeddingCache.getIfPresent(key);
        if (localValue != null && !localValue.isBlank()) {
            log.info("METRIC embeddingCacheHit=1, level=caffeine, key={}", key);
            return localValue;
        }

        String redisValue = stringRedisTemplate.opsForValue().get(key);
        if (redisValue != null && !redisValue.isBlank()) {
            log.info("METRIC embeddingCacheHit=1, level=redis, key={}", key);
            ragEmbeddingCache.put(key, redisValue);
            return redisValue;
        }

        log.info("METRIC embeddingCacheMiss=1, key={}", key);

        String embeddingStr = embeddingService.embedAsVectorString(normalizedQuestion);
        stringRedisTemplate.opsForValue().set(key, embeddingStr, EMBEDDING_TTL);
        ragEmbeddingCache.put(key, embeddingStr);

        log.info("embedding cache write: redis + caffeine, key={}", key);
        return embeddingStr;
    }
//    private String getOrLoadEmbedding(String normalizedQuestion) {
//        log.info("METRIC embeddingCacheMiss=1, key=DISABLED");
//
//        return embeddingService.embedAsVectorString(normalizedQuestion);
//    }

    /**
     * 两级最终答案缓存：Caffeine -> Redis
     * 只有热点问题才会回填答案缓存
     */
    private String getCachedAnswer(String normalizedQuestion, String kbMarker) {
        String key = buildAnswerKey(normalizedQuestion, kbMarker);

        String localValue = ragAnswerCache.getIfPresent(key);
        if (localValue != null && !localValue.isBlank()) {
            log.info("answer cache hit: caffeine, key={}", key);
            return localValue;
        }

        String redisValue = stringRedisTemplate.opsForValue().get(key);
        if (redisValue != null && !redisValue.isBlank()) {
            log.info("answer cache hit: redis, key={}", key);
            ragAnswerCache.put(key, redisValue);
            return redisValue;
        }

        log.info("answer cache miss, key={}", key);
        return null;
    }

    private void cacheAnswer(String normalizedQuestion, String kbMarker, String answer) {
        String key = buildAnswerKey(normalizedQuestion, kbMarker);
        stringRedisTemplate.opsForValue().set(key, answer, ANSWER_TTL);
        ragAnswerCache.put(key, answer);
        log.info("answer cache write: redis + caffeine, key={}", key);
    }

    private List<DocumentChunkVO> getCachedSearchResult(String normalizedQuestion, String kbMarker) {
        String key = buildSearchKey(normalizedQuestion, kbMarker);

        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isBlank()) {
            log.info("search cache miss, key={}", key);
            log.info("METRIC searchCacheMiss=1, key={}", key);
            return null;
        }

        try {
            List<DocumentChunkVO> result = objectMapper.readValue(
                    json,
                    new TypeReference<List<DocumentChunkVO>>() {}
            );

            log.info("search cache hit, key={}, size={}", key, result.size());
            log.info("METRIC searchCacheHit=1, key={}, size={}", key, result.size());

            return result;

        } catch (Exception e) {
            log.warn("search cache parse failed, key={}", key, e);
            log.info("METRIC searchCacheMiss=1, reason=parse_failed, key={}", key);
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    private void clearSearchCache() {
        Set<String> keys = stringRedisTemplate.keys(REDIS_SEARCH_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            log.info("search cache cleared, size={}", keys.size());
        }
    }

    private void cacheSearchResult(String normalizedQuestion, String kbMarker, List<DocumentChunkVO> result) {
        if (result == null || result.isEmpty()) {
            return;
        }

        String key = buildSearchKey(normalizedQuestion, kbMarker);

        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(key, json, SEARCH_TTL);
            log.info("search cache write, key={}, size={}", key, result.size());
        } catch (Exception e) {
            log.warn("search cache write failed, key={}", key, e);
        }
    }

    // =========================
    // 订单 / 工具 / 会话
    // =========================
    public List<Map<String, Object>> listVersionsByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Collections.emptyList();
        }
        return mapper.listVersionsByFileName(fileName.trim());
    }

    public OrderQueryResult queryOrder(Long userId, String orderNo) {
        CustomerOrder order = null;

        if (orderNo != null && !orderNo.isBlank()) {
            order = getOrderByOrderNo(orderNo);
        } else if (userId != null) {
            List<CustomerOrder> orders = listOrdersByUserId(userId);
            if (orders != null && !orders.isEmpty()) {
                order = orders.get(0);
            }
        }

        if (order == null) {
            return null;
        }

        OrderQueryResult result = new OrderQueryResult();
        result.setOrderNo(order.getOrderNo());
        result.setProductName(order.getProductName());
        result.setOrderStatus(order.getOrderStatus());
        result.setPayTime(safe(order.getPayTime()));
        result.setShipTime(safe(order.getShipTime()));
        result.setLogisticsStatus(safe(order.getLogisticsStatus()));
        result.setRefundStatus(safe(order.getRefundStatus()));
        return result;
    }

    private boolean isOrderQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String q = question.trim();

        // 有明确订单号，才走订单查询
        if (q.matches(".*ORD\\d+.*")) {
            return true;
        }

        // 明确说“我的订单 / 查询订单 / 订单状态 / 物流状态”才走订单
        return q.contains("我的订单")
                || q.contains("查询订单")
                || q.contains("订单状态")
                || q.contains("物流状态")
                || q.contains("订单号");
    }

    private boolean needRuleForOrderQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String q = question.trim();

        // 含政策/规则判断类表述时走「订单 + RAG + LLM」，而非纯订单模板
        return q.contains("能不能退")
                || q.contains("能退款")
                || q.contains("能退吗")
                || q.contains("可以退")
                || q.contains("可否退")
                || q.contains("怎么退")
                || q.contains("如何退")
                || q.contains("支持退")
                || q.contains("退款吗")
                || q.contains("退货吗")
                || q.contains("退款规则")
                || q.contains("退货规则")
                || q.contains("退款")
                || q.contains("退货")
                || q.contains("售后")
                || q.contains("赔偿")
                || q.contains("运费")
                || q.contains("超过")
                || q.contains("多久到账");
    }

    private String safe(Object value) {
        return value == null ? "无" : String.valueOf(value);
    }


    /**
     * 纯订单查询：不调用 LLM，直接返回结构化订单信息。
     */
    public AskResult answerOrderDeterministic(String sessionId, String question) {
        CustomerOrder order = resolveOrderForQuestion(question);
        String answer = formatDeterministicOrderAnswer(order, question);
        log.info("METRIC orderPath=deterministic, orderNo={}, question={}",
                order == null ? "none" : order.getOrderNo(), question);
        return AskResult.of(answer);
    }

    /**
     * 订单 + 规则检索（混合 RAG）：检索知识库后由 LLM 综合订单事实与规则作答。
     */
    public AskResult answerOrderWithPolicy(String sessionId, String question) {
        return answerOrderWithPolicy(sessionId, question, search(question));
    }

    /**
     * 订单 + 规则（混合 RAG）：使用编排层已检索的 chunks，避免重复 search。
     */
    public AskResult answerOrderWithPolicy(String sessionId,
                                         String question,
                                         List<DocumentChunkVO> chunks) {
        String orderNo = extractOrderNo(question);
        CustomerOrder order = resolveOrderForQuestion(orderNo, question);
        List<DocumentChunkVO> safeChunks = chunks == null ? List.of() : chunks;

        String orderContext = buildOrderContext(order);
        String knowledgeContext = buildKnowledgeContextFromChunks(safeChunks);
        String prompt = """
                你是一个电商客服助手，请根据订单信息和规则文档回答用户问题。

                要求：
                1. 优先依据订单信息回答（如发货时间、物流状态、退款状态）
                2. 结合规则文档解释能否退货、售后条件等，不要编造规则
                3. 若规则文档与订单状态冲突，以规则文档为准并说明依据
                4. 如果未查询到订单，请说明未查询到相关订单信息
                5. 回答要像客服一样自然、清晰；可引用规则时注明来源文件名

                订单信息：
                %s

                规则文档：
                %s

                用户问题：
                %s
                """.formatted(orderContext, knowledgeContext, question);

        String fallback = formatDeterministicOrderWithPolicyAnswer(order, question, safeChunks);
        log.info("order+policy call llm, orderNo={}, chunkCount={}, question={}",
                order == null ? "none" : order.getOrderNo(), safeChunks.size(), question);
        long llmStart = System.currentTimeMillis();
        String answer = callChatLlm(prompt, fallback);
        long llmCost = System.currentTimeMillis() - llmStart;
        log.info("METRIC llmCostMs={}, route=ORDER_WITH_RAG, question={}", llmCost, question);
        log.info("METRIC orderPath=order_with_policy_llm, orderNo={}, chunkCount={}, question={}",
                order == null ? "none" : order.getOrderNo(), safeChunks.size(), question);
        return AskResult.of(answer, safeChunks);
    }

    private String buildKnowledgeContextFromChunks(List<DocumentChunkVO> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "（未检索到相关规则文档）";
        }
        StringBuilder knowledgeContext = new StringBuilder();
        for (DocumentChunkVO item : chunks) {
            knowledgeContext.append("【来源: ")
                    .append(item.getFileName())
                    .append("，version: ")
                    .append(item.getVersion())
                    .append("，chunkIndex: ")
                    .append(item.getChunkIndex())
                    .append("】\n")
                    .append(item.getContent())
                    .append("\n\n");
        }
        return knowledgeContext.toString();
    }

    private CustomerOrder resolveOrderForQuestion(String question) {
        return resolveOrderForQuestion(extractOrderNo(question), question);
    }

    private CustomerOrder resolveOrderForQuestion(String orderNo, String question) {
        if (orderNo != null) {
            return getOrderByOrderNo(orderNo);
        }
        List<CustomerOrder> orders = listOrdersByUserId(1001L);
        if (orders != null && !orders.isEmpty()) {
            return orders.get(0);
        }
        return null;
    }

    public String formatDeterministicOrderAnswer(CustomerOrder order, String question) {
        if (order == null) {
            return "未查询到相关订单信息，请确认订单号是否正确。";
        }

        String q = question == null ? "" : question.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("已为您查询到订单 ").append(safe(order.getOrderNo())).append("：\n");
        sb.append("商品名称：").append(safe(order.getProductName())).append("\n");
        sb.append("订单状态：").append(safe(order.getOrderStatus())).append("\n");
        sb.append("支付时间：").append(safe(order.getPayTime())).append("\n");
        sb.append("发货时间：").append(safe(order.getShipTime())).append("\n");
        sb.append("物流状态：").append(safe(order.getLogisticsStatus())).append("\n");
        sb.append("退款状态：").append(safe(order.getRefundStatus())).append("\n");

        if (q.contains("物流") || q.contains("快递") || q.contains("发货")) {
            sb.append("\n物流说明：当前物流状态为「")
                    .append(safe(order.getLogisticsStatus()))
                    .append("」。");
        }
        if (q.contains("退") || q.contains("售后")) {
            sb.append("\n退款说明：当前退款状态为「")
                    .append(safe(order.getRefundStatus()))
                    .append("」。");
        }
        return sb.toString().trim();
    }

    private String formatDeterministicOrderWithPolicyAnswer(CustomerOrder order,
                                                            String question,
                                                            List<DocumentChunkVO> chunks) {
        StringBuilder sb = new StringBuilder(formatDeterministicOrderAnswer(order, question));
        if (chunks == null || chunks.isEmpty()) {
            sb.append("\n\n未检索到相关规则文档，如需退款/售后政策说明，请补充具体问题。");
            return sb.toString();
        }
        sb.append("\n\n相关政策参考（来自知识库）：\n");
        int limit = Math.min(3, chunks.size());
        for (int i = 0; i < limit; i++) {
            DocumentChunkVO item = chunks.get(i);
            sb.append(i + 1).append(". [")
                    .append(item.getFileName())
                    .append(", chunkIndex=")
                    .append(item.getChunkIndex())
                    .append("] ")
                    .append(item.getContent())
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private String buildOrderContext(CustomerOrder order) {
        if (order == null) {
            return "未查询到相关订单信息。";
        }

        return """
            订单信息：
            订单号：%s
            商品名称：%s
            订单状态：%s
            支付时间：%s
            发货时间：%s
            物流状态：%s
            退款状态：%s
            """.formatted(
                safe(order.getOrderNo()),
                safe(order.getProductName()),
                safe(order.getOrderStatus()),
                safe(order.getPayTime()),
                safe(order.getShipTime()),
                safe(order.getLogisticsStatus()),
                safe(order.getRefundStatus())
        );
    }

    private String extractOrderNo(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("ORD\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    public List<CustomerOrder> listOrdersByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return customerOrderMapper.findByUserId(userId);
    }

    public CustomerOrder getOrderByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return null;
        }
        return customerOrderMapper.findByOrderNo(orderNo.trim());
    }

    public List<String> listAllFiles() {
        return mapper.findAllFileNames();
    }

    public int countAllFiles() {
        return mapper.countDistinctFileNames();
    }

    public int countChunksOfFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return 0;
        }
        return mapper.countChunksByFileName(fileName.trim());
    }

    private String getConversationMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        String key = "chat:memory:" + sessionId;
        String memory = stringRedisTemplate.opsForValue().get(key);
        return memory == null ? "" : memory;
    }

    private void saveConversationMemory(String sessionId, String question, String answer) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        String key = "chat:memory:" + sessionId;
        String oldMemory = stringRedisTemplate.opsForValue().get(key);
        if (oldMemory == null) {
            oldMemory = "";
        }
        String newMemory = oldMemory
                + "用户：" + question + "\n"
                + "助手：" + answer + "\n";
        stringRedisTemplate.opsForValue().set(key, newMemory, Duration.ofHours(2));
    }

    private String answerOrderOnlyQuestion(String question) {
        CustomerOrder order = resolveOrderForQuestion(question);
        return formatDeterministicOrderAnswer(order, question);
    }

    @Deprecated(since = "reliability-orchestrator", forRemoval = false)
    public AskResult askWithMemory(String sessionId, String question) {
        log.warn("legacy path invoked: askWithMemory. Prefer ChatController -> RagOrchestrator.ask");
        long totalStart = System.currentTimeMillis();
        RouteResult routeResult = routeQuestion(question);
        AskResult result;

        if (routeResult.getRouteType() == RouteType.ORDER_ONLY) {
            String answer = answerOrderOnlyQuestion(question);
            saveConversationMemory(sessionId, question, answer);

            long totalCost = System.currentTimeMillis() - totalStart;
            log.info("METRIC ragTotalCostMs={}, route={}, question={}",
                    totalCost, "ORDER_ONLY", question);

            return AskResult.of(answer);
        }

        if (routeResult.getRouteType() == RouteType.ORDER_WITH_RAG) {
            result = answerOrderWithPolicy(sessionId, question);
            saveConversationMemory(sessionId, question, result.getAnswer());

            long totalCost = System.currentTimeMillis() - totalStart;
            log.info("METRIC ragTotalCostMs={}, route={}, question={}",
                    totalCost, "ORDER_WITH_RAG", question);

            return result;
        }

        if (routeResult.getRouteType() == RouteType.TOOL) {
            String answer = handleToolQuestion(question, routeResult.getToolName());
            saveConversationMemory(sessionId, question, answer);

            long totalCost = System.currentTimeMillis() - totalStart;
            log.info("METRIC ragTotalCostMs={}, route={}, question={}",
                    totalCost, "TOOL", question);

            return AskResult.of(answer);
        }

        result = answerRagQuestion(sessionId, question);
        saveConversationMemory(sessionId, question, result.getAnswer());

        long totalCost = System.currentTimeMillis() - totalStart;
        log.info("METRIC ragTotalCostMs={}, route={}, question={}",
                totalCost, "RAG", question);

        return result;
    }


    private String rewriteQuestion(String memory, String question) {
        if (question == null || question.isBlank()) {
            return question;
        }

        if (memory == null || memory.isBlank()) {
            return question.trim();
        }

        String prompt = """
        你是一个问题改写助手。
        请结合历史对话，将用户当前问题改写成一个完整、明确、适合知识检索的问题。
        
        要求：
        1. 只返回改写后的问题
        2. 不要解释
        3. 如果当前问题已经足够完整，就原样返回
        4. 保留原问题含义，不要编造新信息

        历史对话：
        %s

        当前问题：
        %s
        """.formatted(memory, question);

        String rewritten = callChatLlm(prompt, question.trim());

        if (rewritten == null || rewritten.isBlank()) {
            return question.trim();
        }

        rewritten = rewritten.trim();
        log.info("rewrite question, original={}, rewritten={}", question, rewritten);

        return rewritten;
    }

    /**
     * 只保留两层：
     * 1. 最终问答缓存（热点问题 + 知识库未变化时命中）
     * 2. embedding 缓存（知识库变化后，answer key 因 kbMarker 变化不再命中，此时仍可复用 embedding）
     */
    private AskResult answerRagQuestion(String sessionId, String question) {
        String memory = getConversationMemory(sessionId);
        String rewrittenQuestion = rewriteQuestion(memory, question);
        String normalizedQuestion = normalizeQuestion(rewrittenQuestion);
        String kbMarker = getKnowledgeMarker();

        log.info("rag start, originalQuestion={}, rewrittenQuestion={}, kbMarker={}",
                question, normalizedQuestion, kbMarker);

        boolean hotQuestion = isHotQuestion(normalizedQuestion);

        if (hotQuestion) {
            String cachedAnswer = getCachedAnswer(normalizedQuestion, kbMarker);
            if (cachedAnswer != null) {
                log.info("rag return by answer cache, question={}", normalizedQuestion);
                return AskResult.of(cachedAnswer);
            }
        }

        List<DocumentChunkVO> list = search(normalizedQuestion);

        if (list == null || list.isEmpty()) {
            return AskResult.of("当前知识库无法确定。");
        }

        StringBuilder contextBuilder = new StringBuilder();
        if (list != null) {
            for (DocumentChunkVO item : list) {
                contextBuilder.append("【来源：")
                        .append(item.getFileName())
                        .append("，chunkIndex：")
                        .append(item.getChunkIndex())
                        .append("】\n")
                        .append(item.getContent())
                        .append("\n\n");
            }
        }

        String prompt = """
            你是一个电商客服知识助手，请基于知识库内容回答用户问题。
        
            要求：
            1. 优先使用知识库内容回答
            2. 不要编造知识库中没有的信息
            3. 只要知识库中存在相关内容，就必须总结已有信息进行回答
            4. 如果用户问“主要讲了什么 / 内容是什么 / 总结一下 / 文档讲什么”，请根据检索到的片段总结文档主题和主要内容
            5. 如果知识库只包含部分内容，请说明“根据当前知识库，已知内容包括：”
            6. 只有在知识库内容与问题完全无关时，才回答“当前知识库无法确定”
            7. 回答最后必须附上来源
            8. 来源格式必须是：（来源：文件名，chunkIndex：编号）
        
            历史对话：
            %s
        
            知识库内容：
            %s
        
            当前问题：
            %s
            """.formatted(
                        memory == null ? "" : memory,
                        contextBuilder.toString(),
                        normalizedQuestion
                );

                log.info("rag call llm, originalQuestion={}, rewrittenQuestion={}", question, normalizedQuestion);
                long llmStart = System.currentTimeMillis();
                String fallback = buildLlmUnavailableReply(list);
                String answer = callChatLlm(prompt, fallback);
                long llmCost = System.currentTimeMillis() - llmStart;

                log.info("METRIC llmCostMs={}, question={}", llmCost, question);

                if (answer == null || answer.isBlank()) {
                    answer = "当前知识库无法确定。";
                    log.info("METRIC ragReject=1, reason={}, question={}", "empty_llm_answer", normalizedQuestion);
                }

                if (hotQuestion) {
                    cacheAnswer(normalizedQuestion, kbMarker, answer);
                }

                log.info("rag finish, originalQuestion={}, rewrittenQuestion={}, hot={}",
                        question, normalizedQuestion, hotQuestion);

                return AskResult.of(answer, list);
            }

    private String handleToolQuestion(String question, String toolName) {
        if ("listFiles".equals(toolName)) {
            List<String> files = mapper.findAllFileNames();
            if (files == null || files.isEmpty()) {
                return "当前知识库中没有文件。";
            }
            return "当前知识库中的文件有：" + String.join(", ", files);
        }

        if ("deleteFile".equals(toolName)) {
            String fileName = extractFileName(question);
            if (fileName == null || fileName.isBlank()) {
                return "请提供要删除的文件名。";
            }
            DeleteFileResult result = deleteByFileName(fileName);
            return "已删除文件 " + result.getFileName() + "，共删除 " + result.getDeletedCount() + " 条记录。";
        }

        if ("listVersions".equals(toolName)) {
            String fileName = extractFileName(question);
            if (fileName == null || fileName.isBlank()) {
                return "请提供要查询版本的文件名。";
            }

            List<Map<String, Object>> versions = listVersionsByFileName(fileName);
            if (versions == null || versions.isEmpty()) {
                return "未查询到文件 " + fileName + " 的版本记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("文件 ").append(fileName).append(" 的版本记录：\n");

            for (Map<String, Object> item : versions) {
                sb.append("- version: ").append(item.get("version"));

                if ("ACTIVE".equals(item.get("status"))) {
                    sb.append("（当前版本）");
                }

                sb.append("，status: ").append(item.get("status"))
                        .append("，chunkCount: ").append(item.get("chunk_count"))
                        .append("，startTime: ").append(item.get("start_time"))
                        .append("，endTime: ").append(item.get("end_time"))
                        .append("，docId: ").append(item.get("doc_id"))
                        .append("\n");
            }

            return sb.toString();
        }

        if ("queryTask".equals(toolName)) {
            String taskId = extractTaskId(question);
            if (taskId == null || taskId.isBlank()) {
                return "请提供 taskId。";
            }
            com.example.knowledge_system.entity.UploadTask task = uploadTaskService.getByTaskId(taskId);
            if (task == null) {
                return "未查询到对应任务。";
            }
            return "任务状态：" + task.getStatus()
                    + "，文件名：" + task.getFileName()
                    + (task.getErrorMsg() == null ? "" : "，错误信息：" + task.getErrorMsg());
        }

        return "当前暂不支持该工具操作。";
    }

    private String extractTaskId(String question) {
        if (question == null) {
            return null;
        }

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F\\-]{8,}")
                .matcher(question);

        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
    public AskResult askWithTools(String sessionId, String question) {
        AskResult toolResult = executeAdminTool(question);
        if (toolResult != null) {
            return toolResult;
        }
        return AskResult.of("未识别到可执行的管理工具请求，请明确说明文件列表、版本或任务状态。");
    }

    /**
     * 仅执行管理工具，不做二次业务路由。
     * 返回 null 表示未识别工具请求。
     */
    public AskResult executeAdminTool(String question) {
        if (question == null || question.isBlank()) {
            return AskResult.of("未识别到工具请求，请明确说明要执行的操作。");
        }

        if (question.contains("有哪些文件") || question.contains("文件列表")) {
            return AskResult.of(callMcp("kb_list_files", Map.of()));
        }

        if (question.contains("版本")) {
            String fileName = extractFileName(question);
            if (fileName == null || fileName.isBlank()) {
                return AskResult.of("未识别到文件名，请明确说明，例如 faq.txt");
            }
            return AskResult.of(callMcp("kb_list_versions", Map.of("fileName", fileName)));
        }

        if (question.contains("任务") && question.contains("状态")) {
            String taskId = extractTaskId(question);
            if (taskId == null || taskId.isBlank()) {
                return AskResult.of("未识别到 taskId，请提供上传任务 ID");
            }
            return AskResult.of(callMcp("kb_query_upload_task", Map.of("taskId", taskId)));
        }

        return null;
    }

    private String callMcp(String toolName, Map<String, Object> args) {
        try {
            RestTemplate restTemplate = new RestTemplate();

            Map<String, Object> body = new HashMap<>();
            body.put("name", toolName);
            body.put("arguments", args);

            String url = "http://localhost:8080/mcp/tools/call";

            Map response = restTemplate.postForObject(url, body, Map.class);

            return "工具执行结果：" + response.get("content");

        } catch (Exception e) {
            return "调用工具失败：" + e.getMessage();
        }
    }



//    private String extractTaskId(String question) {
//        // 简单版（你后面可以正则优化）
//        return question.replaceAll("[^a-zA-Z0-9\\-]", "");
//    }

    public String ask(String question) {
        return askWithTools(null, question).getAnswer();
    }

    @Deprecated(since = "reliability-orchestrator", forRemoval = false)
    private RouteResult routeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return new RouteResult(RouteType.RAG, null);
        }

        String q = question.trim();

        if (q.contains("有哪些文件") || q.contains("当前知识库") || q.contains("文件列表")) {
            return new RouteResult(RouteType.TOOL, "listFiles");
        }

        if ((q.contains("版本") || q.contains("历史版本"))
                && (q.contains(".txt") || q.contains(".pdf") || q.contains(".docx"))) {
            return new RouteResult(RouteType.TOOL, "listVersions");
        }

        if ((q.contains("删除文件") || q.contains("删除文档") || q.contains(".txt")) && q.contains("删除")) {
            return new RouteResult(RouteType.TOOL, "deleteFile");
        }

        if (q.contains("任务状态") || q.contains("task") || q.contains("上传状态")) {
            return new RouteResult(RouteType.TOOL, "queryTask");
        }

        if (isOrderQuestion(q)) {
            if (needRuleForOrderQuestion(q)) {
                return new RouteResult(RouteType.ORDER_WITH_RAG, null);
            }
            return new RouteResult(RouteType.ORDER_ONLY, null);
        }

        return new RouteResult(RouteType.RAG, null);
    }

    /**
     * 单个 chunk 保存：不再做语义去重
     */
    public boolean save(String text,
                        String fileName,
                        int chunkIndex,
                        String docType,
                        String status,
                        LocalDateTime startTime,
                        LocalDateTime endTime,
                        String docId,
                        Integer version){

        String lockKey = "lock:vector:save:" + text;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("获取分布式锁失败，请稍后重试");
            }

            List<Double> currentVector = embeddingService.embed(text);
            String currentEmbeddingStr = embeddingService.toVectorString(currentVector);
            String contentHash = md5(text);

            int exists = mapper.existsByDocIdVersionAndHash(docId, version, contentHash);
            if (exists > 0) {
                log.info("skip duplicate chunk in same version, docId={}, version={}, chunkIndex={}",
                        docId, version, chunkIndex);
                return false;
            }

            DocumentChunkVector entity = new DocumentChunkVector();
            entity.setContent(text);
            entity.setEmbedding(currentEmbeddingStr);
            entity.setFileName(fileName);
            entity.setChunkIndex(chunkIndex);
            entity.setContentHash(contentHash);
            entity.setStatus(status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase());
            entity.setStartTime(startTime);
            entity.setEndTime(endTime);
            entity.setDocType(docType == null || docType.isBlank() ? "UNKNOWN" : docType.trim().toUpperCase());
            entity.setDocId(docId);
            entity.setVersion(version);
            entity.setChunkId(docId + "_v" + version + "_c" + chunkIndex);

            mapper.insert(entity);

            bm25SearchService.indexChunk(
                    entity.getId(),
                    fileName,
                    chunkIndex,
                    text,
                    entity.getDocType(),
                    entity.getStatus(),
                    startTime,
                    endTime,
                    entity.getDocId(),
                    entity.getChunkId(),
                    entity.getVersion()
            );

            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("加锁过程被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
    private String normalizeDocType(String docType, String content) {
        if (docType != null && !docType.isBlank()) {
            return docType.trim().toUpperCase();
        }
        return detectDocType(content);
    }

    private String detectDocType(String content) {
        if (content == null || content.isBlank()) {
            return "UNKNOWN";
        }

        String text = content.trim();

        if ((text.contains("问：") && text.contains("答："))
                || (text.contains("Q:") && text.contains("A:"))
                || (text.contains("Q：") && text.contains("A："))) {
            return "FAQ";
        }

        if (text.matches("(?s).*(第[一二三四五六七八九十0-9]+条|\\n\\s*\\d+[\\.、]).*")) {
            return "POLICY";
        }

        if (text.contains("\t") || text.contains("|")) {
            return "TABLE";
        }

        return "LONG_TEXT";
    }
    /**
     * 同文件名整文件覆盖：
     * 先删旧 chunk，再重新切块、重新入库，最后更新 kbMarker
     */
    private UploadResult replaceFileChunks(String fileName,
                                           String content,
                                           String docType,
                                           String status,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime) {

        String fileLockKey = "lock:vector:file:" + fileName;
        RLock fileLock = redissonClient.getLock(fileLockKey);

        boolean locked = false;
        try {
            locked = fileLock.tryLock(3, 30, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("文件正在处理中，请稍后重试: " + fileName);
            }

            String normalized = content == null ? "" : content.replace("\r\n", "\n").trim();

            // 版本化上传：同名文件不物理删除，生成新版本
            String docId = mapper.findDocIdByFileName(fileName);
            if (docId == null || docId.isBlank()) {
                docId = UUID.randomUUID().toString();
            }

            Integer maxVersion = mapper.findMaxVersionByDocId(docId);
            int newVersion = (maxVersion == null ? 1 : maxVersion + 1);

            // PostgreSQL 旧版本失效
            mapper.expireActiveByDocId(docId);

            // BM25 简化处理：删除该文档所有旧 ES 索引，后面只索引新版本
            bm25SearchService.deleteByDocId(docId);

            String finalDocType = normalizeDocType(docType, normalized);
            List<String> chunks = splitText(normalized, finalDocType);

            int total = 0;
            int inserted = 0;
            int skipped = 0;

            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (chunk != null && !chunk.isBlank()) {
                    total++;
                    boolean result = save(chunk.trim(), fileName, i, finalDocType, status, startTime, endTime, docId, newVersion);
                    if (result) {
                        inserted++;
                    } else {
                        skipped++;
                    }
                }
            }

            touchKnowledgeMarker();
            return new UploadResult(total, inserted, skipped);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("文件处理加锁过程被中断", e);
        } finally {
            if (locked && fileLock.isHeldByCurrentThread()) {
                fileLock.unlock();
            }
        }
    }

    public int expireDocuments() {
        int rows = mapper.expireDocuments();
        if (rows > 0) {
            touchKnowledgeMarker();
        }
        return rows;
    }

    public boolean save(String text, String fileName, int chunkIndex) {
        String docId = UUID.randomUUID().toString();
        return save(text, fileName, chunkIndex, null, "ACTIVE", null, null, docId, 1);
    }

    public boolean save(String text) {
        String docId = UUID.randomUUID().toString();
        return save(text, null, 0, null, "ACTIVE", null, null, docId, 1);
    }
    /**
     * 这里只保留 embedding 缓存，不再做 docs cache
     */

    private List<DocumentChunkVO> mergeByRrf(List<DocumentChunkVO> vectorList,
                                             List<DocumentChunkVO> bm25List,
                                             int finalTopK) {
        Map<String, DocumentChunkVO> itemMap = new LinkedHashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        addRrfScores(vectorList, itemMap, scoreMap);
        addRrfScores(bm25List, itemMap, scoreMap);

        return scoreMap.entrySet()
                .stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(finalTopK)
                .map(entry -> itemMap.get(entry.getKey()))
                .toList();
    }

    private void addRrfScores(List<DocumentChunkVO> list,
                              Map<String, DocumentChunkVO> itemMap,
                              Map<String, Double> scoreMap) {
        if (list == null || list.isEmpty()) {
            return;
        }

        int k = 60;

        for (int i = 0; i < list.size(); i++) {
            DocumentChunkVO item = list.get(i);
            if (item == null) {
                continue;
            }

            String key = buildChunkKey(item);

            itemMap.putIfAbsent(key, item);

            double score = 1.0 / (k + i + 1);
            scoreMap.put(key, scoreMap.getOrDefault(key, 0.0) + score);
        }
    }

    private String buildChunkKey(DocumentChunkVO item) {
        return item.getFileName() + "_" + item.getChunkIndex();
    }

    private static final Set<String> RERANK_STOPWORDS = Set.of(
            "什么", "怎么", "如何", "可以", "是否", "多久", "为什么", "哪些",
            "吗", "呢", "啊", "的", "了", "在", "和", "与", "有", "是", "会", "要"
    );

    /** 物流/发货类高频词：仅当 query 明确提及或作为短语命中时才高权重 */
    private static final Set<String> RERANK_GENERIC_TERMS = Set.of(
            "地址", "发货", "物流", "订单", "商品", "用户", "平台", "仓库",
            "付款", "小时", "工作日", "出库", "延迟", "地区", "商家"
    );

    private static final Pattern RERANK_PHRASE_PATTERN =
            Pattern.compile("[\\u4e00-\\u9fa5a-zA-Z0-9]{2,12}");

    private List<DocumentChunkVO> rerankV1(String query,
                                           List<DocumentChunkVO> candidates,
                                           int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> qTokens = extractQueryTokens(query);
        List<String> qPhrases = extractQueryPhrases(query);

        List<ScoredChunk> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            DocumentChunkVO item = candidates.get(i);
            String content = item.getContent() == null ? "" : item.getContent();

            double score = 0.0;

            // 1) 关键词 overlap（区分通用词与主题词）
            score += 3.5 * weightedKeywordOverlap(qTokens, query, content);

            // 2) 完整短语命中（双11、秒杀商品等）
            score += phraseMatchBoost(qPhrases, content);

            // 3) query 与 chunk 来源文件的主题一致性
            score += fileNameThemeBoost(query, item.getFileName());

            // 4) 短文本优先
            score += lengthPenalty(content);

            // 5) docType 加权
            score += docTypeBoost(item.getDocType());

            // 6) RRF 位置先验
            score += 1.0 / (60 + i + 1);

            scored.add(new ScoredChunk(item, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<DocumentChunkVO> result = new ArrayList<>();
        for (int i = 0; i < Math.min(finalTopK, scored.size()); i++) {
            result.add(scored.get(i).item);
        }
        return result;
    }

    private static class ScoredChunk {
        DocumentChunkVO item;
        double score;
        ScoredChunk(DocumentChunkVO item, double score) {
            this.item = item;
            this.score = score;
        }
    }

    private Set<String> tokenize(String text) {
        return extractQueryTokens(text);
    }

    private Set<String> extractQueryTokens(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> tokens = new LinkedHashSet<>();

        Matcher segmentMatcher = RERANK_PHRASE_PATTERN.matcher(text);
        while (segmentMatcher.find()) {
            String segment = segmentMatcher.group();
            if (segment.isBlank() || RERANK_STOPWORDS.contains(segment)) {
                continue;
            }
            tokens.add(segment.toLowerCase());

            if (segment.length() >= 2 && segment.length() <= 8) {
                for (int len = 2; len <= Math.min(4, segment.length()); len++) {
                    for (int i = 0; i <= segment.length() - len; i++) {
                        String gram = segment.substring(i, i + len);
                        if (!RERANK_STOPWORDS.contains(gram)) {
                            tokens.add(gram.toLowerCase());
                        }
                    }
                }
            }
        }

        return tokens;
    }

    private List<String> extractQueryPhrases(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        Matcher matcher = RERANK_PHRASE_PATTERN.matcher(query);
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment.length() >= 2) {
                phrases.add(segment);
            }
            if (segment.length() > 4) {
                for (int len = 3; len <= Math.min(6, segment.length()); len++) {
                    for (int i = 0; i <= segment.length() - len; i++) {
                        phrases.add(segment.substring(i, i + len));
                    }
                }
            }
        }

        return phrases.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .limit(24)
                .toList();
    }

    private double weightedKeywordOverlap(Set<String> qTokens, String query, String content) {
        if (qTokens.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }

        double hitWeight = 0.0;
        double totalWeight = 0.0;

        for (String token : qTokens) {
            double weight = tokenWeight(token, query);
            if (weight <= 0) {
                continue;
            }
            totalWeight += weight;
            if (content.toLowerCase().contains(token) || content.contains(token)) {
                hitWeight += weight;
            }
        }

        if (totalWeight == 0) {
            return 0.0;
        }
        return hitWeight / totalWeight;
    }

    private double tokenWeight(String token, String query) {
        if (token == null || token.isBlank() || RERANK_STOPWORDS.contains(token)) {
            return 0.0;
        }
        if (token.length() == 1) {
            return 0.15;
        }
        if (RERANK_GENERIC_TERMS.contains(token) && !query.contains(token)) {
            return 0.2;
        }
        if (RERANK_GENERIC_TERMS.contains(token)) {
            return 0.45;
        }
        return 1.0;
    }

    private double phraseMatchBoost(List<String> phrases, String content) {
        if (phrases == null || phrases.isEmpty() || content == null || content.isBlank()) {
            return 0.0;
        }

        double boost = 0.0;
        for (String phrase : phrases) {
            if (phrase == null || phrase.length() < 2) {
                continue;
            }
            if (content.contains(phrase)) {
                boost += 1.2 + Math.min(phrase.length(), 8) * 0.25;
            }
        }
        return boost;
    }

    private double fileNameThemeBoost(String query, String fileName) {
        if (query == null || query.isBlank() || fileName == null || fileName.isBlank()) {
            return 0.0;
        }

        String fileKey = fileName.toLowerCase();
        double boost = 0.0;

        if (query.contains("双11") && fileKey.contains("double11")) {
            boost += 0.9;
        }
        if ((query.contains("优惠券") || query.contains("满减") || query.contains("券"))
                && fileKey.contains("coupon")) {
            boost += 0.65;
        }
        if ((query.contains("物流") || query.contains("轨迹") || query.contains("签收") || query.contains("承运"))
                && fileKey.contains("logistics")) {
            boost += 0.65;
        }
        if ((query.contains("退款") || query.contains("到账") || query.contains("原路"))
                && fileKey.contains("refund")) {
            boost += 0.65;
        }
        if ((query.contains("退货") || query.contains("无理由"))
                && fileKey.contains("return")) {
            boost += 0.55;
        }
        if (query.contains("发货") && fileKey.contains("shipping") && !query.contains("双11")) {
            boost += 0.45;
        }
        if (query.contains("双11") && query.contains("发货") && fileKey.contains("double11")) {
            boost += 0.75;
        }

        return boost;
    }

    private double lengthPenalty(String content) {
        int len = content.length();
        if (len <= 200) return 0.5;
        if (len <= 500) return 0.2;
        return 0.0; // 太长不给加分
    }

    private double docTypeBoost(String docType) {
        if (docType == null) return 0.0;
        return switch (docType) {
            case "FAQ" -> 0.6;
            case "POLICY" -> 0.5;
            case "TABLE" -> 0.3;
            case "LONG_TEXT" -> 0.1;
            default -> 0.0;
        };
    }

//    private List<DocumentChunkVO> rerankWithLLM(String query,
//                                                List<DocumentChunkVO> candidates,
//                                                int finalTopK) {
//
//        // 只取前10条送入LLM（控制成本）
//        List<DocumentChunkVO> top = candidates.stream().limit(10).toList();
//
//        StringBuilder options = new StringBuilder();
//        for (int i = 0; i < top.size(); i++) {
//            options.append("[").append(i).append("] ").append(top.get(i).getContent()).append("\n");
//        }
//
//        String prompt = """
//    请根据用户问题，从下面候选段落中选出最相关的前 %d 条，并返回对应的编号列表（按相关性排序）。
//
//    用户问题：
//    %s
//
//    候选段落：
//    %s
//
//    只返回编号数组，例如：[2,0,1]
//    """.formatted(finalTopK, query, options);
//
//        String resp = chatClient.prompt().user(prompt).call().content();
//
//        List<Integer> idx = parseIndexList(resp);
//        List<DocumentChunkVO> result = new ArrayList<>();
//        for (Integer i : idx) {
//            if (i >= 0 && i < top.size()) result.add(top.get(i));
//        }
//        return result;
//    }
    private List<DocumentChunkVO> deduplicateByContent(List<DocumentChunkVO> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> seen = new HashSet<>();
        List<DocumentChunkVO> result = new ArrayList<>();

        for (DocumentChunkVO item : list) {
            if (item == null || item.getContent() == null) {
                continue;
            }

            String hash = md5(item.getContent().trim());
            if (seen.add(hash)) {
                result.add(item);
            }
        }

        return result;
    }

    private List<DocumentChunkVO> expandChunks(List<DocumentChunkVO> list) {
        List<DocumentChunkVO> result = new ArrayList<>();

        if (list == null || list.isEmpty()) {
            return result;
        }

        for (DocumentChunkVO item : list) {
            if (item == null) {
                continue;
            }

            DocumentChunkVO newItem = new DocumentChunkVO();

            newItem.setId(item.getId());
            newItem.setFileName(item.getFileName());
            newItem.setChunkIndex(item.getChunkIndex());
            newItem.setScore(item.getScore());
            newItem.setDocType(item.getDocType());
            newItem.setDocId(item.getDocId());
            newItem.setChunkId(item.getChunkId());
            newItem.setVersion(item.getVersion());
            newItem.setStatus(item.getStatus());
            newItem.setStartTime(item.getStartTime());
            newItem.setEndTime(item.getEndTime());
            newItem.setUpdatedAt(item.getUpdatedAt());

            if (item.getVersion() == null) {
                newItem.setContent(item.getContent());
                result.add(newItem);
                continue;
            }

            int start = Math.max(0, item.getChunkIndex() - 1);
            int end = item.getChunkIndex() + 1;

            List<DocumentChunkVector> neighbors = mapper.findNeighborChunks(
                    item.getFileName(),
                    item.getVersion(),
                    start,
                    end
            );

            if (neighbors == null || neighbors.isEmpty()) {
                newItem.setContent(item.getContent());
            } else {
                String merged = neighbors.stream()
                        .map(DocumentChunkVector::getContent)
                        .collect(Collectors.joining("\n"));
                newItem.setContent(merged);
            }

            result.add(newItem);
        }

        return result;
    }

    public List<DocumentChunkVO> search(String query) {
        long searchStart = System.currentTimeMillis();
        String normalizedQuestion = normalizeQuestion(query);
        log.info("hybrid search start, question={}", normalizedQuestion);

        String kbMarker = getKnowledgeMarker();

        List<DocumentChunkVO> cachedResult = getCachedSearchResult(normalizedQuestion, kbMarker);
        if (cachedResult != null) {
            long searchCost = System.currentTimeMillis() - searchStart;
            log.info("METRIC searchCostMs={}, question={}, finalSize={}, cache={}",
                    searchCost, normalizedQuestion, cachedResult.size(), "hit");
            return cachedResult;
        }

        List<DocumentChunkVO> vectorResult;
        try {
            String embeddingStr = getOrLoadEmbedding(normalizedQuestion);
            vectorResult = mapper.searchSimilar(embeddingStr);

            if (vectorResult == null) {
                vectorResult = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("vector search failed, degrade to bm25 only, question={}", normalizedQuestion, e);
            log.info("METRIC retrievalDegrade=1, channel={}, question={}", "vector", normalizedQuestion);
            vectorResult = Collections.emptyList();
        }

        List<DocumentChunkVO> bm25Result;
        try {
            bm25Result = bm25SearchService.search(normalizedQuestion, 10);

            if (bm25Result == null) {
                bm25Result = Collections.emptyList();
            }
        } catch (Exception e) {
            log.warn("bm25 search failed, degrade to vector only, question={}", normalizedQuestion, e);
            log.info("METRIC retrievalDegrade=1, channel={}, question={}", "bm25", normalizedQuestion);
            bm25Result = Collections.emptyList();
        }

        if (vectorResult.isEmpty() && bm25Result.isEmpty()) {
            log.warn("all retrieval channels empty, question={}", normalizedQuestion);

            long searchCost = System.currentTimeMillis() - searchStart;
            log.info("METRIC searchCostMs={}, question={}, finalSize={}, cache={}",
                    searchCost, normalizedQuestion, 0, "miss");
            log.info("METRIC ragReject=1, reason={}, question={}",
                    "empty_retrieval", normalizedQuestion);

            return Collections.emptyList();
        }

        List<DocumentChunkVO> filteredVectorResult = vectorResult.stream()
                .filter(item -> item.getScore() != null && item.getScore() >= VECTOR_SCORE_THRESHOLD)
                .toList();

        List<DocumentChunkVO> filteredBm25Result = bm25Result.stream()
                .filter(item -> item.getScore() != null && item.getScore() >= BM25_SCORE_THRESHOLD)
                .toList();

        if (!filteredVectorResult.isEmpty()) {
            vectorResult = filteredVectorResult;
        }

        if (!filteredBm25Result.isEmpty()) {
            bm25Result = filteredBm25Result;
        }

        List<DocumentChunkVO> merged = mergeByRrf(vectorResult, bm25Result, 30);

        List<DocumentChunkVO> deduped = deduplicateByContent(merged);

        List<DocumentChunkVO> expanded = expandChunks(deduped);

        List<DocumentChunkVO> finalResult = rerankV1(normalizedQuestion, expanded, 5);

        if (finalResult == null || finalResult.isEmpty()) {
            long searchCost = System.currentTimeMillis() - searchStart;
            log.info("METRIC searchCostMs={}, question={}, finalSize={}, cache={}",
                    searchCost, normalizedQuestion, 0, "miss");
            log.info("METRIC ragReject=1, reason={}, question={}",
                    "below_threshold", normalizedQuestion);
            return Collections.emptyList();
        }

        cacheSearchResult(normalizedQuestion, kbMarker, finalResult);

        log.info("hybrid search finish, question={}, vectorSize={}, bm25Size={}, mergedSize={}, dedupedSize={}, expandedSize={}, finalSize={}",
                normalizedQuestion,
                vectorResult.size(),
                bm25Result.size(),
                merged.size(),
                deduped.size(),
                expanded.size(),
                finalResult.size());

        long searchCost = System.currentTimeMillis() - searchStart;
        log.info("METRIC searchCostMs={}, question={}, finalSize={}, cache={}",
                searchCost, normalizedQuestion, finalResult.size(), "miss");

        return finalResult;
    }

    public UploadResult uploadTextContent(String fileName, String content) {
        return replaceFileChunks(fileName, content, null, "ACTIVE", null, null);
    }

    private LocalDateTime parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(text.trim());
    }

    public MultiUploadResult uploadTxtFiles(MultipartFile[] files,
                                            String docType,
                                            String status,
                                            String startTimeStr,
                                            String endTimeStr) throws IOException {

        int fileCount = 0;
        int totalChunks = 0;
        int insertedChunks = 0;
        int skippedChunks = 0;

        LocalDateTime startTime = parseDateTime(startTimeStr);
        LocalDateTime endTime = parseDateTime(endTimeStr);
        String finalStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.trim().toUpperCase();

        if (files == null || files.length == 0) {
            return new MultiUploadResult(0, 0, 0, 0);
        }

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }

            fileCount++;

            String fileName = file.getOriginalFilename();
            String content = new String(file.getBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .trim();

            UploadResult result = replaceFileChunks(fileName, content, docType, finalStatus, startTime, endTime);

            totalChunks += result.getTotal();
            insertedChunks += result.getInserted();
            skippedChunks += result.getSkipped();
        }

        return new MultiUploadResult(fileCount, totalChunks, insertedChunks, skippedChunks);
    }

    public UploadResult uploadTxtFile(MultipartFile file,
                                      String docType,
                                      String status,
                                      String startTimeStr,
                                      String endTimeStr) throws IOException {
        if (file == null || file.isEmpty()) {
            return new UploadResult(0, 0, 0);
        }

        LocalDateTime startTime = parseDateTime(startTimeStr);
        LocalDateTime endTime = parseDateTime(endTimeStr);
        String finalStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.trim().toUpperCase();

        String fileName = file.getOriginalFilename();
        String content = new String(file.getBytes(), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .trim();

        return replaceFileChunks(fileName, content, docType, finalStatus, startTime, endTime);
    }

    public UploadResult uploadTxtFile(MultipartFile file) throws IOException {
        return uploadTxtFile(file, null, "ACTIVE", null, null);
    }

    public UploadResult uploadTextContent(String fileName,
                                          String content,
                                          String docType,
                                          String status,
                                          String startTimeStr,
                                          String endTimeStr) {

        LocalDateTime startTime = parseDateTime(startTimeStr);
        LocalDateTime endTime = parseDateTime(endTimeStr);
        String finalStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.trim().toUpperCase();

        return replaceFileChunks(fileName, content, docType, finalStatus, startTime, endTime);
    }
    private List<String> splitFaq(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").trim();

        String[] parts = normalized.split("(?=问[:：]|Q[:：])");

        for (String part : parts) {
            String chunk = part.trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }

        if (chunks.isEmpty()) {
            chunks.add(normalized);
        }

        return chunks;
    }

    private List<String> splitPolicy(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").trim();

        String[] parts = normalized.split("(?=第[一二三四五六七八九十0-9]+条|\\n\\s*\\d+[\\.、])");

        for (String part : parts) {
            String chunk = part.trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
        }

        if (chunks.isEmpty()) {
            chunks.add(normalized);
        }

        return chunks;
    }

    private List<String> splitTable(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").trim();
        String[] lines = normalized.split("\n");

        StringBuilder current = new StringBuilder();
        int maxChars = 800;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }

            if (current.length() + line.length() > maxChars && current.length() > 0) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }

            current.append(line.trim()).append("\n");
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    private List<String> splitWithOverlap(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replace("\r\n", "\n").trim();

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());

            if (end < normalized.length()) {
                int sentenceEnd = Math.max(
                        Math.max(normalized.lastIndexOf("。", end), normalized.lastIndexOf("\n", end)),
                        normalized.lastIndexOf("；", end)
                );

                if (sentenceEnd > start + chunkSize / 2) {
                    end = sentenceEnd + 1;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            start = Math.max(0, end - overlap);
        }

        return chunks;
    }

    public List<String> splitText(String text, String docType) {
        String type = normalizeDocType(docType, text);

        return switch (type) {
            case "FAQ" -> splitFaq(text);
            case "POLICY" -> splitPolicy(text);
            case "TABLE" -> splitTable(text);
            case "LONG_TEXT" -> splitWithOverlap(text, 500, 80);
            default -> splitWithOverlap(text, 500, 80);
        };
    }

    public String rollbackDocument(String docId, Integer version) {
        if (docId == null || docId.isBlank()) {
            throw new RuntimeException("docId不能为空");
        }
        if (version == null || version <= 0) {
            throw new RuntimeException("version不合法");
        }

        List<DocumentChunkVector> chunks = mapper.findByDocIdAndVersion(docId, version);
        if (chunks == null || chunks.isEmpty()) {
            return "未找到 docId=" + docId + " 的 version=" + version;
        }

        // 1. 先把当前 ACTIVE 版本置为 EXPIRED
        mapper.expireActiveByDocId(docId);

        // 2. 激活指定版本
        mapper.activateVersion(docId, version);

        // 3. 删除 ES/BM25 中该文档旧索引
        bm25SearchService.deleteByDocId(docId);

        // 4. 重建指定版本的 BM25 索引
        for (DocumentChunkVector chunk : chunks) {
            bm25SearchService.indexChunk(
                    chunk.getId(),
                    chunk.getFileName(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getDocType(),
                    "ACTIVE",
                    chunk.getStartTime(),
                    chunk.getEndTime(),
                    chunk.getDocId(),
                    chunk.getChunkId(),
                    chunk.getVersion()
            );
        }

        // 5. 更新知识库 marker，让答案缓存失效
        touchKnowledgeMarker();

        return "已回滚 docId=" + docId + " 到 version=" + version;
    }

    public DeleteFileResult deleteByFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new RuntimeException("文件名不能为空");
        }

        String cleanFileName = fileName.trim();

        int deletedCount = mapper.deleteByFileName(cleanFileName);

        // 同步删除 BM25/ES 索引
        bm25SearchService.deleteByFileName(cleanFileName);

        if (deletedCount > 0) {
            touchKnowledgeMarker();
        }

        return new DeleteFileResult(cleanFileName, deletedCount);
    }

    private String extractFileName(String question) {
        if (question == null) {
            return null;
        }

        String[] parts = question.split("\\s+");
        for (String part : parts) {
            if (part.endsWith(".txt") || part.endsWith(".pdf") || part.endsWith(".docx")) {
                return part.trim();
            }
        }

        int txtIndex = question.indexOf(".txt");
        if (txtIndex > 0) {
            int start = txtIndex;
            while (start > 0) {
                char c = question.charAt(start - 1);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                    start--;
                } else {
                    break;
                }
            }
            return question.substring(start, txtIndex + 4).trim();
        }

        return null;
    }

    // =========================
    // Reliability Layer hooks
    // =========================

    public boolean isOrderQuestionForRouter(String question) {
        return isOrderQuestion(question);
    }

    public boolean needRuleForOrderQuestionForRouter(String question) {
        return needRuleForOrderQuestion(question);
    }

    public String rewriteQuestionForSession(String sessionId, String question) {
        return rewriteQuestion(getConversationMemory(sessionId), question);
    }

    public String normalizeQuestionForSearch(String question) {
        return normalizeQuestion(question);
    }

    public void persistConversationMemory(String sessionId, String question, String answer) {
        saveConversationMemory(sessionId, question, answer);
    }

    public void clearConversationMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        stringRedisTemplate.delete("chat:memory:" + sessionId);
    }

    public String buildRefuseReply() {
        return "您的问题涉及高风险事项，当前无法自动处理。已为您记录，请通过人工客服渠道继续咨询。";
    }

    public String buildFallbackReply(String question) {
        return "当前问题暂时无法识别，请换一种说法后重试。";
    }

    public String buildInsufficientReply(EvidenceAssessment evidence) {
        String reason = evidence != null && evidence.getReason() != null
                ? evidence.getReason()
                : "evidence insufficient";
        return "根据当前知识库，暂无足够依据准确回答您的问题（" + reason + "）。请补充更多细节，或联系人工客服。";
    }

    public String buildConflictReply(List<DocumentChunkVO> chunks, EvidenceAssessment evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前知识库中存在不一致的规则说明，暂无法给出单一结论。请参考以下来源并确认具体场景：\n");
        int limit = Math.min(3, chunks == null ? 0 : chunks.size());
        for (int i = 0; i < limit; i++) {
            DocumentChunkVO item = chunks.get(i);
            sb.append("- ").append(item.getFileName())
                    .append(" chunk=").append(item.getChunkIndex()).append("\n");
        }
        if (evidence != null && !evidence.getConflicts().isEmpty()) {
            sb.append("冲突说明：").append(String.join("；", evidence.getConflicts()));
        }
        return sb.toString();
    }

    public String generateNoRagReply(String sessionId, String question, String intent) {
        String memory = getConversationMemory(sessionId);
        String prompt = """
                你是一个电商客服助手。当前任务不需要查询知识库。
                任务类型：%s
                历史对话：
                %s
                用户问题：
                %s
                请直接回答，不要编造订单或政策数据。
                """.formatted(intent, memory == null ? "" : memory, question);
        String answer = callChatLlm(prompt, "好的，请问还有什么可以帮您？");
        return answer == null || answer.isBlank() ? "好的，请问还有什么可以帮您？" : answer.trim();
    }

    public AskResult generateRagAnswer(String sessionId,
                                       String originalQuestion,
                                       String normalizedQuestion,
                                       List<DocumentChunkVO> chunks,
                                       EvidenceStatus evidenceStatus) {
        if (chunks == null || chunks.isEmpty()) {
            return AskResult.of(buildInsufficientReply(null));
        }

        String memory = getConversationMemory(sessionId);
        StringBuilder contextBuilder = new StringBuilder();
        for (DocumentChunkVO item : chunks) {
            contextBuilder.append("【来源：")
                    .append(item.getFileName())
                    .append("，chunkIndex：")
                    .append(item.getChunkIndex())
                    .append("】\n")
                    .append(item.getContent())
                    .append("\n\n");
        }

        String prompt = buildRagPrompt(evidenceStatus, memory, contextBuilder.toString(), normalizedQuestion);

        log.info("rag call llm, evidenceStatus={}, originalQuestion={}, rewrittenQuestion={}",
                evidenceStatus, originalQuestion, normalizedQuestion);
        long llmStart = System.currentTimeMillis();
        String fallback = buildLlmUnavailableReply(chunks);
        String answer = callChatLlm(prompt, fallback);
        long llmCost = System.currentTimeMillis() - llmStart;
        log.info("METRIC llmCostMs={}, question={}", llmCost, originalQuestion);

        if (answer == null || answer.isBlank()) {
            answer = buildInsufficientReply(null);
            log.info("METRIC ragReject=1, reason={}, question={}", "empty_llm_answer", normalizedQuestion);
        }

        return AskResult.of(answer, chunks);
    }

    private String callChatLlm(String prompt, String fallback) {
        try {
            String answer = chatClient.prompt().user(prompt).call().content();
            if (answer == null || answer.isBlank()) {
                return fallback;
            }
            return answer.trim();
        } catch (Exception e) {
            Throwable root = com.example.knowledge_system.util.LlmExceptionDiagnostics.rootCause(e);
            log.error("llm call failed: {}", com.example.knowledge_system.util.LlmExceptionDiagnostics.format(e));
            log.info("METRIC llmUnavailable=1, reason={}, rootCause={}, rootMessage={}",
                    e.getClass().getSimpleName(),
                    root == null ? "unknown" : root.getClass().getSimpleName(),
                    root == null ? "" : root.getMessage());
            return fallback;
        }
    }

    private String buildOrderLlmFallback(CustomerOrder order, List<DocumentChunkVO> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("大模型服务暂时不可用（无法连接 DeepSeek，请检查网络、代理或 API 配置）。\n");
        sb.append("以下为系统查询到的订单信息，供参考：\n\n");
        sb.append(buildOrderContext(order));
        if (chunks != null && !chunks.isEmpty()) {
            sb.append("\n相关规则文档摘要：\n");
            appendChunkSummaries(sb, chunks, 3);
        }
        return sb.toString();
    }

    private String buildLlmUnavailableReply(List<DocumentChunkVO> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("大模型服务暂时不可用（无法连接 DeepSeek，请检查网络、代理或 API 配置）。");
        sb.append("以下为知识库检索结果摘要，供参考：\n");
        appendChunkSummaries(sb, chunks, 3);
        return sb.toString();
    }

    private void appendChunkSummaries(StringBuilder sb, List<DocumentChunkVO> chunks, int maxItems) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        int limit = Math.min(maxItems, chunks.size());
        for (int i = 0; i < limit; i++) {
            DocumentChunkVO item = chunks.get(i);
            String content = item.getContent();
            if (content != null && content.length() > 120) {
                content = content.substring(0, 120) + "...";
            }
            sb.append("- [").append(item.getFileName()).append("] ").append(content).append("\n");
        }
    }

    private String buildRagPrompt(EvidenceStatus evidenceStatus,
                                  String memory,
                                  String context,
                                  String normalizedQuestion) {
        if (evidenceStatus == EvidenceStatus.PARTIAL) {
            return """
                    你是电商客服知识助手。只能基于知识库中【已明确出现】的信息回答。
                    要求：
                    1. 只回答证据充分的部分
                    2. 对无法确认的部分必须明确写「当前知识库无法确认：...」
                    3. 不要编造
                    4. 回答末尾附来源（来源：文件名，chunkIndex：编号）
                    历史对话：
                    %s
                    知识库内容：
                    %s
                    当前问题：
                    %s
                    """.formatted(memory == null ? "" : memory, context, normalizedQuestion);
        }

        return """
                你是一个电商客服知识助手，请基于知识库内容回答用户问题。
                要求：
                1. 优先使用知识库内容回答
                2. 不要编造知识库中没有的信息
                3. 只要知识库中存在相关内容，就必须总结已有信息进行回答
                4. 只有在知识库内容与问题完全无关时，才回答「当前知识库无法确定」
                5. 回答最后必须附上来源（来源：文件名，chunkIndex：编号）
                历史对话：
                %s
                知识库内容：
                %s
                当前问题：
                %s
                """.formatted(memory == null ? "" : memory, context, normalizedQuestion);
    }
}
