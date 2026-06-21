package com.example.knowledge_system.reliability.dto;

public class FailureAssessment {

    private boolean shouldHandoff;
    private String severity;
    private String handoffReason;
    private String failureType;
    private String summaryForAgent;

    public boolean isShouldHandoff() {
        return shouldHandoff;
    }

    public void setShouldHandoff(boolean shouldHandoff) {
        this.shouldHandoff = shouldHandoff;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getHandoffReason() {
        return handoffReason;
    }

    public void setHandoffReason(String handoffReason) {
        this.handoffReason = handoffReason;
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public String getSummaryForAgent() {
        return summaryForAgent;
    }

    public void setSummaryForAgent(String summaryForAgent) {
        this.summaryForAgent = summaryForAgent;
    }
}
