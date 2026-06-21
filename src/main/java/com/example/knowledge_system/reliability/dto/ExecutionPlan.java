package com.example.knowledge_system.reliability.dto;

public class ExecutionPlan {

    private AnswerMode answerMode;
    private EvidenceStatus evidenceStatus;
    private FinalOutcome outcome;
    private boolean useLlm;
    private String reason;

    public AnswerMode getAnswerMode() {
        return answerMode;
    }

    public void setAnswerMode(AnswerMode answerMode) {
        this.answerMode = answerMode;
    }

    public EvidenceStatus getEvidenceStatus() {
        return evidenceStatus;
    }

    public void setEvidenceStatus(EvidenceStatus evidenceStatus) {
        this.evidenceStatus = evidenceStatus;
    }

    public FinalOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(FinalOutcome outcome) {
        this.outcome = outcome;
    }

    public boolean isUseLlm() {
        return useLlm;
    }

    public void setUseLlm(boolean useLlm) {
        this.useLlm = useLlm;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
