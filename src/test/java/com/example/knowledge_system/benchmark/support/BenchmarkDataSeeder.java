package com.example.knowledge_system.benchmark.support;

import com.example.knowledge_system.mapper.DocumentChunkVectorMapper;
import com.example.knowledge_system.service.VectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDataSeeder.class);
    private static final String FAQ_FILE = "benchmark_faq_100.txt";
    private static final String ORDER_PREFIX = "ORD2026BENCH";
    private static final int FAQ_COUNT = 100;
    private static final int DOC_COUNT = 100;
    private static final int ORDER_COUNT = 1000;

    private final VectorService vectorService;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunkVectorMapper chunkMapper;

    public BenchmarkDataSeeder(VectorService vectorService,
                               JdbcTemplate jdbcTemplate,
                               DocumentChunkVectorMapper chunkMapper) {
        this.vectorService = vectorService;
        this.jdbcTemplate = jdbcTemplate;
        this.chunkMapper = chunkMapper;
    }

    public Map<String, Object> ensureBenchmarkDataset() {
        Map<String, Object> stats = new LinkedHashMap<>();
        int faqChunks = safeCount(() -> chunkMapper.countChunksByFileName(FAQ_FILE));
        int policyFiles = safeCount(this::countPolicyFiles);
        int orderCount = countBenchmarkOrders();

        if (faqChunks < FAQ_COUNT) {
            faqChunks = trySeedFaq();
        }

        if (policyFiles < DOC_COUNT) {
            policyFiles = trySeedPolicyDocuments(policyFiles);
        }

        if (orderCount < ORDER_COUNT) {
            log.info("Seeding {} benchmark orders", ORDER_COUNT);
            seedOrders(ORDER_COUNT);
            orderCount = countBenchmarkOrders();
        }

        stats.put("faqFile", FAQ_FILE);
        stats.put("faqChunks", faqChunks);
        stats.put("policyDocuments", policyFiles);
        stats.put("totalKnowledgeFiles", safeCount(() -> chunkMapper.countDistinctFileNames()));
        stats.put("orders", orderCount);
        stats.put("sampleOrderNo", sampleOrderNo());
        return stats;
    }

    public String sampleOrderNo() {
        return ORDER_PREFIX + "000001";
    }

    public void ensureMinimalOrder() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE order_no = ?",
                Integer.class,
                sampleOrderNo()
        );
        if (count != null && count > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                """
                INSERT INTO customer_order
                (user_id, order_no, product_name, order_status, pay_time, ship_time, logistics_status, refund_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                1001L,
                sampleOrderNo(),
                "Benchmark基准商品",
                "已发货",
                now.minusDays(3),
                now.minusDays(2),
                "运输中",
                "无"
        );
    }

    private int trySeedFaq() {
        try {
            log.info("Seeding {} FAQ entries into {}", FAQ_COUNT, FAQ_FILE);
            vectorService.uploadTextContent(FAQ_FILE, buildFaqContent(), "FAQ", "ACTIVE", null, null);
            return chunkMapper.countChunksByFileName(FAQ_FILE);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Skip FAQ seeding due to embedding dimension mismatch. Re-index knowledge base for Ollama (768-dim).");
            return safeCount(() -> chunkMapper.countChunksByFileName(FAQ_FILE));
        }
    }

    private int trySeedPolicyDocuments(int existingCount) {
        int inserted = existingCount;
        for (int i = existingCount + 1; i <= DOC_COUNT; i++) {
            try {
                vectorService.uploadTextContent(
                        "benchmark_policy_%03d.txt".formatted(i),
                        buildPolicyContent(i),
                        "POLICY",
                        "ACTIVE",
                        null,
                        null
                );
                inserted++;
            } catch (DataIntegrityViolationException ex) {
                log.warn("Stop policy seeding at {} due to embedding dimension mismatch.", i);
                statsNote(ex.getMostSpecificCause().getMessage());
                break;
            }
        }
        return countPolicyFiles();
    }

    private void statsNote(String message) {
        log.warn("Benchmark seed note: {}", message);
    }

    private int safeCount(IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (Exception ex) {
            log.warn("Count failed: {}", ex.getMessage());
            return 0;
        }
    }

    private int countPolicyFiles() {
        List<String> files = chunkMapper.findAllFileNames();
        return (int) files.stream().filter(name -> name.startsWith("benchmark_policy_")).count();
    }

    private int countBenchmarkOrders() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE order_no LIKE ?",
                Integer.class,
                ORDER_PREFIX + "%"
        );
        return count == null ? 0 : count;
    }

    private void seedOrders(int targetCount) {
        jdbcTemplate.update("DELETE FROM customer_order WHERE order_no LIKE ?", ORDER_PREFIX + "%");

        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO customer_order
                (user_id, order_no, product_name, order_status, pay_time, ship_time, logistics_status, refund_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                        int index = i + 1;
                        ps.setLong(1, 1001L);
                        ps.setString(2, ORDER_PREFIX + String.format("%06d", index));
                        ps.setString(3, "Benchmark商品" + index);
                        ps.setString(4, index % 3 == 0 ? "待发货" : "已发货");
                        ps.setObject(5, now.minusDays(index % 30 + 1));
                        ps.setObject(6, index % 3 == 0 ? null : now.minusDays(index % 10));
                        ps.setString(7, index % 3 == 0 ? "待发货" : "运输中");
                        ps.setString(8, index % 5 == 0 ? "退款处理中" : "无");
                    }

                    @Override
                    public int getBatchSize() {
                        return targetCount;
                    }
                }
        );
    }

    private String buildFaqContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= FAQ_COUNT; i++) {
            sb.append("问：Benchmark FAQ问题").append(i).append("是什么？\n");
            sb.append("答：这是benchmark FAQ第").append(i).append("条的标准答案，适用于电商客服测试。\n\n");
        }
        return sb.toString();
    }

    private String buildPolicyContent(int index) {
        return """
                Benchmark政策文档 #%d
                适用范围：退换货、物流、优惠券、售后处理。
                商品名称示例：Benchmark商品%d
                退款规则：未发货订单1-3个工作日原路退回；已发货需先退货。
                物流规则：发货后24小时内更新物流轨迹，偏远地区可能延迟3-5天。
                优惠券规则：满300减50，部分活动商品不参与优惠券。
                """.formatted(index, index);
    }

    @FunctionalInterface
    private interface IntSupplier {
        int getAsInt() throws Exception;
    }
}
