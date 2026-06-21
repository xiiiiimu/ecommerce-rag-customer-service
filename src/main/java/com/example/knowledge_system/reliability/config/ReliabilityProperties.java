package com.example.knowledge_system.reliability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "reliability")
public class ReliabilityProperties {

    private boolean enabled = false;
    private int escalationThreshold = 8;
    private int escalationDecayPerTurn = 1;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getEscalationThreshold() {
        return escalationThreshold;
    }

    public void setEscalationThreshold(int escalationThreshold) {
        this.escalationThreshold = escalationThreshold;
    }

    public int getEscalationDecayPerTurn() {
        return escalationDecayPerTurn;
    }

    public void setEscalationDecayPerTurn(int escalationDecayPerTurn) {
        this.escalationDecayPerTurn = escalationDecayPerTurn;
    }
}
