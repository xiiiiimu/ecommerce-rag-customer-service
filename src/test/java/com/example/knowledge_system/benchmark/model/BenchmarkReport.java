package com.example.knowledge_system.benchmark.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkReport {

    private Instant generatedAt = Instant.now();
    private String environmentNote;
    private GroupMetrics groupA;
    private GroupBMetrics groupB;
    private GroupCMetrics groupC;
    private List<ReliabilityResult> reliability = new ArrayList<>();

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getEnvironmentNote() {
        return environmentNote;
    }

    public void setEnvironmentNote(String environmentNote) {
        this.environmentNote = environmentNote;
    }

    public GroupMetrics getGroupA() {
        return groupA;
    }

    public void setGroupA(GroupMetrics groupA) {
        this.groupA = groupA;
    }

    public GroupBMetrics getGroupB() {
        return groupB;
    }

    public void setGroupB(GroupBMetrics groupB) {
        this.groupB = groupB;
    }

    public GroupCMetrics getGroupC() {
        return groupC;
    }

    public void setGroupC(GroupCMetrics groupC) {
        this.groupC = groupC;
    }

    public List<ReliabilityResult> getReliability() {
        return reliability;
    }

    public void setReliability(List<ReliabilityResult> reliability) {
        this.reliability = reliability;
    }

    public static class GroupMetrics {
        private String label;
        private List<QueryResult> queries = new ArrayList<>();
        private double avgResponseTimeMs;
        private double avgRetrievalTimeMs;
        private double avgLlmTimeMs;
        private double cacheHitRate;

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public List<QueryResult> getQueries() {
            return queries;
        }

        public void setQueries(List<QueryResult> queries) {
            this.queries = queries;
        }

        public double getAvgResponseTimeMs() {
            return avgResponseTimeMs;
        }

        public void setAvgResponseTimeMs(double avgResponseTimeMs) {
            this.avgResponseTimeMs = avgResponseTimeMs;
        }

        public double getAvgRetrievalTimeMs() {
            return avgRetrievalTimeMs;
        }

        public void setAvgRetrievalTimeMs(double avgRetrievalTimeMs) {
            this.avgRetrievalTimeMs = avgRetrievalTimeMs;
        }

        public double getAvgLlmTimeMs() {
            return avgLlmTimeMs;
        }

        public void setAvgLlmTimeMs(double avgLlmTimeMs) {
            this.avgLlmTimeMs = avgLlmTimeMs;
        }

        public double getCacheHitRate() {
            return cacheHitRate;
        }

        public void setCacheHitRate(double cacheHitRate) {
            this.cacheHitRate = cacheHitRate;
        }
    }

    public static class GroupBMetrics {
        private GroupMetrics warmupRun;
        private GroupMetrics warmRun;
        private double latencyImprovementPct;
        private Map<String, Double> coldVsWarmResponseTime = new LinkedHashMap<>();

        public GroupMetrics getWarmupRun() {
            return warmupRun;
        }

        public void setWarmupRun(GroupMetrics warmupRun) {
            this.warmupRun = warmupRun;
        }

        public GroupMetrics getWarmRun() {
            return warmRun;
        }

        public void setWarmRun(GroupMetrics warmRun) {
            this.warmRun = warmRun;
        }

        public double getLatencyImprovementPct() {
            return latencyImprovementPct;
        }

        public void setLatencyImprovementPct(double latencyImprovementPct) {
            this.latencyImprovementPct = latencyImprovementPct;
        }

        private Map<String, Double> coldResponseByCategory = new LinkedHashMap<>();
        private Map<String, Double> warmResponseByCategory = new LinkedHashMap<>();

        public Map<String, Double> getColdResponseByCategory() {
            return coldResponseByCategory;
        }

        public void setColdResponseByCategory(Map<String, Double> coldResponseByCategory) {
            this.coldResponseByCategory = coldResponseByCategory;
        }

        public Map<String, Double> getWarmResponseByCategory() {
            return warmResponseByCategory;
        }

        public void setWarmResponseByCategory(Map<String, Double> warmResponseByCategory) {
            this.warmResponseByCategory = warmResponseByCategory;
        }

        public Map<String, Double> getColdVsWarmResponseTime() {
            return coldVsWarmResponseTime;
        }

        public void setColdVsWarmResponseTime(Map<String, Double> coldVsWarmResponseTime) {
            this.coldVsWarmResponseTime = coldVsWarmResponseTime;
        }
    }

    public static class GroupCMetrics {
        private Map<String, Object> seedStats = new LinkedHashMap<>();
        private List<QueryResult> queries = new ArrayList<>();
        private double retrievalRecall;
        private double avgRetrievalLatencyMs;
        private double avgLlmLatencyMs;
        private double avgEndToEndLatencyMs;
        private int recallSampleSize;
        private int recallHits;

        public Map<String, Object> getSeedStats() {
            return seedStats;
        }

        public void setSeedStats(Map<String, Object> seedStats) {
            this.seedStats = seedStats;
        }

        public List<QueryResult> getQueries() {
            return queries;
        }

        public void setQueries(List<QueryResult> queries) {
            this.queries = queries;
        }

        public double getRetrievalRecall() {
            return retrievalRecall;
        }

        public void setRetrievalRecall(double retrievalRecall) {
            this.retrievalRecall = retrievalRecall;
        }

        public double getAvgRetrievalLatencyMs() {
            return avgRetrievalLatencyMs;
        }

        public void setAvgRetrievalLatencyMs(double avgRetrievalLatencyMs) {
            this.avgRetrievalLatencyMs = avgRetrievalLatencyMs;
        }

        public double getAvgLlmLatencyMs() {
            return avgLlmLatencyMs;
        }

        public void setAvgLlmLatencyMs(double avgLlmLatencyMs) {
            this.avgLlmLatencyMs = avgLlmLatencyMs;
        }

        public double getAvgEndToEndLatencyMs() {
            return avgEndToEndLatencyMs;
        }

        public void setAvgEndToEndLatencyMs(double avgEndToEndLatencyMs) {
            this.avgEndToEndLatencyMs = avgEndToEndLatencyMs;
        }

        public int getRecallSampleSize() {
            return recallSampleSize;
        }

        public void setRecallSampleSize(int recallSampleSize) {
            this.recallSampleSize = recallSampleSize;
        }

        public int getRecallHits() {
            return recallHits;
        }

        public void setRecallHits(int recallHits) {
            this.recallHits = recallHits;
        }
    }

    public static class QueryResult {
        private String category;
        private String question;
        private long responseTimeMs;
        private long retrievalTimeMs;
        private long llmTimeMs;
        private int cacheHits;
        private int cacheMisses;
        private int chunkCount;
        private boolean success;
        private String answerPreview;
        private String sessionId;

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public long getResponseTimeMs() {
            return responseTimeMs;
        }

        public void setResponseTimeMs(long responseTimeMs) {
            this.responseTimeMs = responseTimeMs;
        }

        public long getRetrievalTimeMs() {
            return retrievalTimeMs;
        }

        public void setRetrievalTimeMs(long retrievalTimeMs) {
            this.retrievalTimeMs = retrievalTimeMs;
        }

        public long getLlmTimeMs() {
            return llmTimeMs;
        }

        public void setLlmTimeMs(long llmTimeMs) {
            this.llmTimeMs = llmTimeMs;
        }

        public int getCacheHits() {
            return cacheHits;
        }

        public void setCacheHits(int cacheHits) {
            this.cacheHits = cacheHits;
        }

        public int getCacheMisses() {
            return cacheMisses;
        }

        public void setCacheMisses(int cacheMisses) {
            this.cacheMisses = cacheMisses;
        }

        public int getChunkCount() {
            return chunkCount;
        }

        public void setChunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getAnswerPreview() {
            return answerPreview;
        }

        public void setAnswerPreview(String answerPreview) {
            this.answerPreview = answerPreview;
        }

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    public static class ReliabilityResult {
        private String scenario;
        private boolean degradationTriggered;
        private boolean handoffTriggered;
        private boolean fallbackAnswerReturned;
        private String answerPreview;
        private List<String> observedMetrics = new ArrayList<>();
        private boolean passed;

        public String getScenario() {
            return scenario;
        }

        public void setScenario(String scenario) {
            this.scenario = scenario;
        }

        public boolean isDegradationTriggered() {
            return degradationTriggered;
        }

        public void setDegradationTriggered(boolean degradationTriggered) {
            this.degradationTriggered = degradationTriggered;
        }

        public boolean isHandoffTriggered() {
            return handoffTriggered;
        }

        public void setHandoffTriggered(boolean handoffTriggered) {
            this.handoffTriggered = handoffTriggered;
        }

        public boolean isFallbackAnswerReturned() {
            return fallbackAnswerReturned;
        }

        public void setFallbackAnswerReturned(boolean fallbackAnswerReturned) {
            this.fallbackAnswerReturned = fallbackAnswerReturned;
        }

        public String getAnswerPreview() {
            return answerPreview;
        }

        public void setAnswerPreview(String answerPreview) {
            this.answerPreview = answerPreview;
        }

        public List<String> getObservedMetrics() {
            return observedMetrics;
        }

        public void setObservedMetrics(List<String> observedMetrics) {
            this.observedMetrics = observedMetrics;
        }

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }
    }
}
