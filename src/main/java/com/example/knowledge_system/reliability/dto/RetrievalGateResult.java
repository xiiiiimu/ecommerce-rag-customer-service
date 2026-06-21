package com.example.knowledge_system.reliability.dto;

public class RetrievalGateResult {

    private boolean shouldRetrieve;
    private double retrievalValue;
    private String reason;

    public boolean isShouldRetrieve() {
        return shouldRetrieve;
    }

    public void setShouldRetrieve(boolean shouldRetrieve) {
        this.shouldRetrieve = shouldRetrieve;
    }

    public double getRetrievalValue() {
        return retrievalValue;
    }

    public void setRetrievalValue(double retrievalValue) {
        this.retrievalValue = retrievalValue;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
