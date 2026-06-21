package com.example.knowledge_system.dto;

public class IntentResult {
    private String intent;
    private String tool;

    public IntentResult(){
    }

    public IntentResult(String intent, String tool){
        this.intent = intent;
        this.tool = tool;
    }

    public String getIntent(){
        return intent;
    }
    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }
}
