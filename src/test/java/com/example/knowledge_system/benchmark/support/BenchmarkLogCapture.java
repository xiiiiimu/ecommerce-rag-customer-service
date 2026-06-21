package com.example.knowledge_system.benchmark.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BenchmarkLogCapture implements AutoCloseable {

    private static final Pattern SEARCH_COST = Pattern.compile("METRIC searchCostMs=(\\d+)");
    private static final Pattern LLM_COST = Pattern.compile("METRIC llmCostMs=(\\d+)");
    private static final Pattern TOTAL_COST = Pattern.compile("METRIC ragTotalCostMs=(\\d+)");
    private static final Pattern EMBEDDING_HIT = Pattern.compile("METRIC embeddingCacheHit=1");
    private static final Pattern EMBEDDING_MISS = Pattern.compile("METRIC embeddingCacheMiss=1");
    private static final Pattern SEARCH_HIT = Pattern.compile("METRIC searchCacheHit=1");
    private static final Pattern SEARCH_MISS = Pattern.compile("METRIC searchCacheMiss=1");
    private static final Pattern RETRIEVAL_DEGRADE = Pattern.compile("METRIC retrievalDegrade=1, channel=([^,\\s]+)");
    private static final Pattern LLM_UNAVAILABLE = Pattern.compile("METRIC llmUnavailable=1");

    private final ListAppender<ILoggingEvent> appender;
    private final Logger logger;

    public BenchmarkLogCapture() {
        logger = (Logger) LoggerFactory.getLogger("com.example.knowledge_system");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    public CapturedMetrics snapshot() {
        StringBuilder combined = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            combined.append(event.getFormattedMessage()).append('\n');
        }
        return parse(combined.toString());
    }

    public void reset() {
        appender.list.clear();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }

    public static CapturedMetrics parse(String logs) {
        CapturedMetrics metrics = new CapturedMetrics();
        metrics.searchCostMs = lastLong(SEARCH_COST, logs);
        metrics.llmCostMs = sumLong(LLM_COST, logs);
        metrics.totalCostMs = lastLong(TOTAL_COST, logs);
        metrics.embeddingCacheHits = count(EMBEDDING_HIT, logs);
        metrics.embeddingCacheMisses = count(EMBEDDING_MISS, logs);
        metrics.searchCacheHits = count(SEARCH_HIT, logs);
        metrics.searchCacheMisses = count(SEARCH_MISS, logs);
        metrics.retrievalDegrades = findAll(RETRIEVAL_DEGRADE, logs);
        metrics.llmUnavailable = logs.contains("METRIC llmUnavailable=1");
        return metrics;
    }

    private static long lastLong(Pattern pattern, String logs) {
        long value = 0;
        Matcher matcher = pattern.matcher(logs);
        while (matcher.find()) {
            value = Long.parseLong(matcher.group(1));
        }
        return value;
    }

    private static long sumLong(Pattern pattern, String logs) {
        long sum = 0;
        Matcher matcher = pattern.matcher(logs);
        while (matcher.find()) {
            sum += Long.parseLong(matcher.group(1));
        }
        return sum;
    }

    private static int count(Pattern pattern, String logs) {
        Matcher matcher = pattern.matcher(logs);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static List<String> findAll(Pattern pattern, String logs) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(logs);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    public static class CapturedMetrics {
        private long searchCostMs;
        private long llmCostMs;
        private long totalCostMs;
        private int embeddingCacheHits;
        private int embeddingCacheMisses;
        private int searchCacheHits;
        private int searchCacheMisses;
        private List<String> retrievalDegrades = List.of();
        private boolean llmUnavailable;

        public long getSearchCostMs() {
            return searchCostMs;
        }

        public long getLlmCostMs() {
            return llmCostMs;
        }

        public long getTotalCostMs() {
            return totalCostMs;
        }

        public int getEmbeddingCacheHits() {
            return embeddingCacheHits;
        }

        public int getEmbeddingCacheMisses() {
            return embeddingCacheMisses;
        }

        public int getSearchCacheHits() {
            return searchCacheHits;
        }

        public int getSearchCacheMisses() {
            return searchCacheMisses;
        }

        public int totalCacheHits() {
            return embeddingCacheHits + searchCacheHits;
        }

        public int totalCacheMisses() {
            return embeddingCacheMisses + searchCacheMisses;
        }

        public double cacheHitRate() {
            int total = totalCacheHits() + totalCacheMisses();
            return total == 0 ? 0.0 : (double) totalCacheHits() / total;
        }

        public List<String> getRetrievalDegrades() {
            return retrievalDegrades;
        }

        public boolean isLlmUnavailable() {
            return llmUnavailable;
        }
    }
}
