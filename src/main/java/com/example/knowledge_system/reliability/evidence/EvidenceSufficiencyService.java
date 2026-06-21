package com.example.knowledge_system.reliability.evidence;

import com.example.knowledge_system.dto.DocumentChunkVO;
import com.example.knowledge_system.reliability.dto.EvidenceAssessment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EvidenceSufficiencyService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceSufficiencyService.class);

    private static final double TOP1_LOW = 0.30;
    private static final double COMPOSITE_HIGH = 0.65;
    private static final double COMPOSITE_MEDIUM = 0.45;

    private static final String[] BUSINESS_SLOTS = {
            "退款", "物流", "订单", "优惠券", "发货", "活动", "价格", "库存", "赔偿", "投诉", "政策"
    };

    private static final String[] QUESTION_TYPE_SLOTS = {
            "时间", "多久", "条件", "规则", "金额", "状态", "原因", "什么时候", "如何", "怎么", "多少", "期限", "有效期", "截止"
    };

    private static final String[] CORE_AGREEMENT_KEYWORDS = {
            "退款", "物流", "订单", "优惠券", "发货", "活动", "价格", "库存", "赔偿", "政策", "支持", "不支持"
    };

    private static final String[] TIME_SENSITIVE_MARKERS = {
            "活动", "优惠券", "促销", "价格", "库存", "物流时效", "退款周期", "有效期", "截止日期", "政策更新",
            "限时", "满减", "折扣", "运费", "到货时间", "发货时间"
    };

    private static final String[] STABLE_CONTENT_MARKERS = {
            "操作手册", "FAQ", "常见问题", "产品说明", "技术文档", "使用说明", "功能介绍", "安装", "配置"
    };

    public EvidenceAssessment evaluate(String question, List<DocumentChunkVO> chunks) {
        EvidenceAssessment assessment = new EvidenceAssessment();

        if (chunks == null || chunks.isEmpty()) {
            assessment.setSufficient(false);
            assessment.setConfidence("low");
            assessment.setAnswerableScope("none");
            assessment.setReason("no retrieval results");
            assessment.setCompositeScore(0.0);
            assessment.getMissingInfo().add("knowledge base returned empty");
            return assessment;
        }

        List<DocumentChunkVO> participating = filterParticipatingChunks(chunks);
        if (participating.isEmpty()) {
            assessment.setSufficient(false);
            assessment.setConfidence("low");
            assessment.setAnswerableScope("none");
            assessment.setReason("all retrieval results expired or invalid");
            assessment.setCompositeScore(0.0);
            assessment.getMissingInfo().add("evidence expired");
            return assessment;
        }

        double top1 = score(participating.get(0));
        double top3Gap = topGap(participating, 3);
        double coverage = queryCoverage(question, participating.get(0).getContent());
        double agreement = sourceAgreement(participating);
        double freshness = aggregateFreshness(participating);

        double composite = 0.4 * top1 + 0.3 * coverage + 0.2 * agreement + 0.1 * freshness;
        assessment.setCompositeScore(composite);

        List<String> conflicts = detectConflicts(participating);
        assessment.setConflicts(conflicts);

        if (top1 < TOP1_LOW) {
            assessment.setSufficient(false);
            assessment.setConfidence("low");
            assessment.setAnswerableScope("none");
            assessment.setReason("top1 score below threshold");
            assessment.getMissingInfo().add("low relevance score");
            return assessment;
        }

        if (!conflicts.isEmpty()) {
            assessment.setSufficient(false);
            assessment.setConfidence("low");
            assessment.setAnswerableScope("partial");
            assessment.setReason("conflicting sources in top chunks");
            return assessment;
        }

        if (composite >= COMPOSITE_HIGH && coverage >= 0.35) {
            assessment.setSufficient(true);
            assessment.setConfidence("high");
            assessment.setAnswerableScope("full");
            assessment.setReason("strong top score and query coverage");
            return assessment;
        }

        if (composite >= COMPOSITE_MEDIUM || (top1 >= 0.4 && top3Gap >= 0.05)) {
            assessment.setSufficient(true);
            assessment.setConfidence("medium");
            assessment.setAnswerableScope("partial");
            assessment.setReason("partial evidence, answer with scope limit");
            if (coverage < 0.35) {
                assessment.getMissingInfo().add("query not fully covered by top chunk");
            }
            return assessment;
        }

        assessment.setSufficient(false);
        assessment.setConfidence("low");
        assessment.setAnswerableScope("none");
        assessment.setReason("composite score too low");
        assessment.getMissingInfo().add("insufficient evidence to answer reliably");
        return assessment;
    }

    private List<DocumentChunkVO> filterParticipatingChunks(List<DocumentChunkVO> chunks) {
        List<DocumentChunkVO> result = new ArrayList<>();
        for (DocumentChunkVO chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            if (freshness(chunk) <= 0.0) {
                continue;
            }
            result.add(chunk);
        }
        return result;
    }

    private double aggregateFreshness(List<DocumentChunkVO> chunks) {
        int limit = Math.min(3, chunks.size());
        double sum = 0.0;
        for (int i = 0; i < limit; i++) {
            sum += freshness(chunks.get(i));
        }
        return sum / limit;
    }

    private double score(DocumentChunkVO chunk) {
        return chunk.getScore() == null ? 0.0 : chunk.getScore();
    }

    private double topGap(List<DocumentChunkVO> chunks, int k) {
        if (chunks.size() < 2) {
            return score(chunks.get(0));
        }
        int limit = Math.min(k, chunks.size());
        double first = score(chunks.get(0));
        double third = score(chunks.get(limit - 1));
        return Math.max(0, first - third);
    }

    private double queryCoverage(String question, String content) {
        if (question == null || content == null || content.isBlank()) {
            return 0.0;
        }

        List<String> businessSlots = matchedSlots(question, BUSINESS_SLOTS);
        List<String> typeSlots = matchedSlots(question, QUESTION_TYPE_SLOTS);

        if (businessSlots.isEmpty() && typeSlots.isEmpty()) {
            return legacyTokenCoverage(question, content);
        }

        double businessCoverage = slotCoverage(businessSlots, content);
        double typeCoverage = slotCoverage(typeSlots, content);

        if (businessSlots.isEmpty()) {
            return Math.min(0.55, typeCoverage);
        }
        if (typeSlots.isEmpty()) {
            return Math.min(0.60, businessCoverage);
        }

        if (typeCoverage < 0.2) {
            return Math.min(0.55, 0.75 * businessCoverage + 0.25 * typeCoverage);
        }
        return 0.6 * businessCoverage + 0.4 * typeCoverage;
    }

    private double slotCoverage(List<String> slots, String content) {
        if (slots.isEmpty()) {
            return 1.0;
        }
        int hit = 0;
        for (String slot : slots) {
            if (content.contains(slot)) {
                hit++;
            }
        }
        return (double) hit / slots.size();
    }

    private List<String> matchedSlots(String text, String[] slots) {
        List<String> matched = new ArrayList<>();
        for (String slot : slots) {
            if (text.contains(slot)) {
                matched.add(slot);
            }
        }
        return matched;
    }

    private double legacyTokenCoverage(String question, String content) {
        Set<String> tokens = tokenize(question);
        if (tokens.isEmpty()) {
            return 0.0;
        }
        int hit = 0;
        for (String token : tokens) {
            if (token.length() >= 2 && content.contains(token)) {
                hit++;
            }
        }
        return Math.min(0.5, (double) hit / tokens.size());
    }

    private double sourceAgreement(List<DocumentChunkVO> chunks) {
        if (chunks.size() < 2) {
            return 1.0;
        }

        List<String> conflicts = detectConflicts(chunks);
        if (!conflicts.isEmpty()) {
            return 0.35;
        }

        int limit = Math.min(3, chunks.size());
        Map<String, Integer> keywordHits = new HashMap<>();
        for (int i = 0; i < limit; i++) {
            String content = chunks.get(i).getContent();
            if (content == null) {
                continue;
            }
            for (String keyword : CORE_AGREEMENT_KEYWORDS) {
                if (content.contains(keyword)) {
                    keywordHits.merge(keyword, 1, Integer::sum);
                }
            }
        }

        boolean sharedCoreKeyword = keywordHits.values().stream().anyMatch(count -> count >= 2);
        if (sharedCoreKeyword) {
            return 1.0;
        }

        Set<String> files = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            String fileName = chunks.get(i).getFileName();
            if (fileName != null) {
                files.add(fileName);
            }
        }
        return files.size() <= 2 ? 0.75 : 0.5;
    }

    private double freshness(DocumentChunkVO chunk) {
        if (!isTimeSensitive(chunk)) {
            return 1.0;
        }

        if (!hasUsableTimeMetadata(chunk)) {
            // TODO: enrich DocumentChunkVO with reliable updatedAt/expireDate from ingestion pipeline
            log.debug("time-sensitive chunk missing temporal metadata, freshness fallback=1.0, chunkId={}",
                    chunk.getChunkId());
            return 1.0;
        }

        if (isExpired(chunk)) {
            return 0.0;
        }

        return computeTimeSensitiveFreshness(chunk);
    }

    private boolean isTimeSensitive(DocumentChunkVO chunk) {
        if (isStableContent(chunk)) {
            return false;
        }
        String combined = combineText(chunk.getContent(), chunk.getFileName(), chunk.getDocType());
        for (String marker : TIME_SENSITIVE_MARKERS) {
            if (combined.contains(marker)) {
                return true;
            }
        }
        String docType = chunk.getDocType() == null ? "" : chunk.getDocType().toLowerCase();
        return docType.contains("promo")
                || docType.contains("coupon")
                || docType.contains("policy")
                || docType.contains("price");
    }

    private boolean isStableContent(DocumentChunkVO chunk) {
        String combined = combineText(chunk.getContent(), chunk.getFileName(), chunk.getDocType());
        for (String marker : STABLE_CONTENT_MARKERS) {
            if (combined.contains(marker)) {
                return true;
            }
        }
        String docType = chunk.getDocType() == null ? "" : chunk.getDocType().toLowerCase();
        return docType.contains("manual") || docType.contains("faq") || docType.contains("guide");
    }

    private boolean hasUsableTimeMetadata(DocumentChunkVO chunk) {
        return chunk.getStartTime() != null
                || chunk.getEndTime() != null
                || chunk.getUpdatedAt() != null;
    }

    private boolean isExpired(DocumentChunkVO chunk) {
        if ("EXPIRED".equalsIgnoreCase(chunk.getStatus())) {
            return true;
        }
        LocalDateTime now = LocalDateTime.now();
        if (chunk.getEndTime() != null && chunk.getEndTime().isBefore(now)) {
            return true;
        }
        return chunk.getStartTime() != null && chunk.getStartTime().isAfter(now);
    }

    private double computeTimeSensitiveFreshness(DocumentChunkVO chunk) {
        LocalDateTime now = LocalDateTime.now();

        if (chunk.getEndTime() != null) {
            long daysToExpire = ChronoUnit.DAYS.between(now, chunk.getEndTime());
            if (daysToExpire >= 90) {
                return 1.0;
            }
            if (daysToExpire <= 0) {
                return 0.0;
            }
            return Math.max(0.3, daysToExpire / 90.0);
        }

        if (chunk.getStartTime() != null && chunk.getStartTime().isAfter(now)) {
            return 0.2;
        }

        if (chunk.getUpdatedAt() != null) {
            long ageDays = ChronoUnit.DAYS.between(chunk.getUpdatedAt(), now);
            if (ageDays <= 30) {
                return 1.0;
            }
            if (ageDays >= 365) {
                return 0.5;
            }
            return 1.0 - ((ageDays - 30.0) / 335.0) * 0.5;
        }

        return 1.0;
    }

    private String combineText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                sb.append(part).append(' ');
            }
        }
        return sb.toString();
    }

    private List<String> detectConflicts(List<DocumentChunkVO> chunks) {
        List<String> conflicts = new ArrayList<>();
        if (chunks.size() < 2) {
            return conflicts;
        }
        String c1 = chunks.get(0).getContent() == null ? "" : chunks.get(0).getContent();
        String c2 = chunks.get(1).getContent() == null ? "" : chunks.get(1).getContent();
        if (c1.contains("不支持") && c2.contains("支持")) {
            conflicts.add("top chunks disagree on support policy");
        }
        if (c1.contains("不可") && c2.contains("可以")) {
            conflicts.add("top chunks contain contradictory modal verbs");
        }
        return conflicts;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        String[] parts = text.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]+", " ").split("\\s+");
        for (String part : parts) {
            if (part != null && part.length() >= 2) {
                tokens.add(part);
            }
        }
        if (tokens.isEmpty() && text.length() >= 2) {
            tokens.add(text.trim());
        }
        return tokens;
    }
}
