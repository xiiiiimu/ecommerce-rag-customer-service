package com.example.knowledge_system.reliability.model;

import java.util.ArrayList;
import java.util.List;

public class ConversationState {

    private int turnCount;
    private List<String> intentHistory = new ArrayList<>();
    private int failureCount;
    /** Session cumulative count of ANSWER turns with PARTIAL/INSUFFICIENT evidence (not a consecutive streak). */
    private int lowConfidenceCount;
    private List<String> riskFlags = new ArrayList<>();
    private int userRepeatedQuestionCount;
    private String conversationState = "NORMAL";
    private boolean handoffOffered;
    private int escalationScore;
    private List<String> recentQuestions = new ArrayList<>();
    private List<String> recentAnswerModes = new ArrayList<>();

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }

    public List<String> getIntentHistory() {
        return intentHistory;
    }

    public void setIntentHistory(List<String> intentHistory) {
        this.intentHistory = intentHistory != null ? intentHistory : new ArrayList<>();
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public int getLowConfidenceCount() {
        return lowConfidenceCount;
    }

    public void setLowConfidenceCount(int lowConfidenceCount) {
        this.lowConfidenceCount = lowConfidenceCount;
    }

    public List<String> getRiskFlags() {
        return riskFlags;
    }

    public void setRiskFlags(List<String> riskFlags) {
        this.riskFlags = riskFlags != null ? riskFlags : new ArrayList<>();
    }

    public int getUserRepeatedQuestionCount() {
        return userRepeatedQuestionCount;
    }

    public void setUserRepeatedQuestionCount(int userRepeatedQuestionCount) {
        this.userRepeatedQuestionCount = userRepeatedQuestionCount;
    }

    public String getConversationState() {
        return conversationState;
    }

    public void setConversationState(String conversationState) {
        this.conversationState = conversationState;
    }

    public boolean isHandoffOffered() {
        return handoffOffered;
    }

    public void setHandoffOffered(boolean handoffOffered) {
        this.handoffOffered = handoffOffered;
    }

    public int getEscalationScore() {
        return escalationScore;
    }

    public void setEscalationScore(int escalationScore) {
        this.escalationScore = escalationScore;
    }

    public List<String> getRecentQuestions() {
        return recentQuestions;
    }

    public void setRecentQuestions(List<String> recentQuestions) {
        this.recentQuestions = recentQuestions != null ? recentQuestions : new ArrayList<>();
    }

    public List<String> getRecentAnswerModes() {
        return recentAnswerModes;
    }

    public void setRecentAnswerModes(List<String> recentAnswerModes) {
        this.recentAnswerModes = recentAnswerModes != null ? recentAnswerModes : new ArrayList<>();
    }
}
