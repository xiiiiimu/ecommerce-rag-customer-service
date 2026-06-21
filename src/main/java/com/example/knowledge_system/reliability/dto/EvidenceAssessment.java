package com.example.knowledge_system.reliability.dto;

import java.util.ArrayList;
import java.util.List;

public class EvidenceAssessment {

    private boolean sufficient;
    private String confidence;
    private String answerableScope;
    private List<String> conflicts = new ArrayList<>();
    private List<String> missingInfo = new ArrayList<>();
    private String reason;
    private double compositeScore;

    public boolean isSufficient() {
        return sufficient;
    }

    public void setSufficient(boolean sufficient) {
        this.sufficient = sufficient;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getAnswerableScope() {
        return answerableScope;
    }

    public void setAnswerableScope(String answerableScope) {
        this.answerableScope = answerableScope;
    }

    public List<String> getConflicts() {
        return conflicts;
    }

    public void setConflicts(List<String> conflicts) {
        this.conflicts = conflicts != null ? conflicts : new ArrayList<>();
    }

    public List<String> getMissingInfo() {
        return missingInfo;
    }

    public void setMissingInfo(List<String> missingInfo) {
        this.missingInfo = missingInfo != null ? missingInfo : new ArrayList<>();
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getCompositeScore() {
        return compositeScore;
    }

    public void setCompositeScore(double compositeScore) {
        this.compositeScore = compositeScore;
    }
}
