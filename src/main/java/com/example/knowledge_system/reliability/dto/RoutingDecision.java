package com.example.knowledge_system.reliability.dto;

public class RoutingDecision {

    private String intent;
    private boolean needRetrieval;
    private String riskLevel;
    private boolean requiresTool;
    private boolean requiresClarification;
    private String suggestedPath;
    private String reason;
    private double ruleConfidence;

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public boolean isNeedRetrieval() {
        return needRetrieval;
    }

    public void setNeedRetrieval(boolean needRetrieval) {
        this.needRetrieval = needRetrieval;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isRequiresTool() {
        return requiresTool;
    }

    public void setRequiresTool(boolean requiresTool) {
        this.requiresTool = requiresTool;
    }

    public boolean isRequiresClarification() {
        return requiresClarification;
    }

    public void setRequiresClarification(boolean requiresClarification) {
        this.requiresClarification = requiresClarification;
    }

    public String getSuggestedPath() {
        return suggestedPath;
    }

    public void setSuggestedPath(String suggestedPath) {
        this.suggestedPath = suggestedPath;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getRuleConfidence() {
        return ruleConfidence;
    }

    public void setRuleConfidence(double ruleConfidence) {
        this.ruleConfidence = ruleConfidence;
    }
}
