package com.example.knowledge_system.dto;

public class AskRequest {

    private String sessionId;
    private String question;

    public String getSessionId(){
        return sessionId;
    }

    public void setSessionId(String sessionId){
        this.sessionId = sessionId;
    }

    public String getQuestion(){
        return question;
    }
    public void setQuestion(String question){
        this.question = question;
    }


}
