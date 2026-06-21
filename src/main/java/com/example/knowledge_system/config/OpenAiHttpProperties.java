package com.example.knowledge_system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.openai.http")
public class OpenAiHttpProperties {

    /**
     * AUTO: follow JVM http(s)/socks* system properties.
     * DIRECT: force no proxy for OpenAI HTTP client (bypass broken SOCKS).
     * HTTP: use explicit HTTP proxy host/port below.
     * SOCKS: use explicit SOCKS proxy host/port below.
     */
    private ProxyMode proxyMode = ProxyMode.HTTP;

    private String proxyHost = "127.0.0.1";

    private int proxyPort = 7890;

    private Duration connectTimeout = Duration.ofSeconds(30);

    private Duration readTimeout = Duration.ofSeconds(120);

    public enum ProxyMode {
        AUTO, DIRECT, HTTP, SOCKS
    }

    public ProxyMode getProxyMode() {
        return proxyMode;
    }

    public void setProxyMode(ProxyMode proxyMode) {
        this.proxyMode = proxyMode;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
