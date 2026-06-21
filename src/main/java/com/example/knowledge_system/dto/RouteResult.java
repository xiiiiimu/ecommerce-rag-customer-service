package com.example.knowledge_system.dto;

public class RouteResult {

    private RouteType routeType;
    private String toolName;

    public RouteResult() {
    }

    public RouteResult(RouteType routeType, String toolName) {
        this.routeType = routeType;
        this.toolName = toolName;
    }

    public RouteType getRouteType() {
        return routeType;
    }

    public void setRouteType(RouteType routeType) {
        this.routeType = routeType;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }
}
