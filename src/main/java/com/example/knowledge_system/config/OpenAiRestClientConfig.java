package com.example.knowledge_system.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;

@Slf4j
@Configuration
@EnableConfigurationProperties(OpenAiHttpProperties.class)
public class OpenAiRestClientConfig {

    @Bean
    @Primary
    public RestClient.Builder openAiRestClientBuilder(OpenAiHttpProperties properties) {
        Proxy proxy = resolveProxy(properties);
        log.info("OpenAI RestClient init: proxyMode={}, proxyHost={}, proxyPort={}, actualProxy={}",
                properties.getProxyMode(),
                properties.getProxyHost(),
                properties.getProxyPort(),
                describeProxyInstance(proxy));
        logJvmSocksProxyWarningIfPresent(properties.getProxyMode());
        return RestClient.builder().requestFactory(openAiRequestFactory(properties, proxy));
    }

    public static ClientHttpRequestFactory openAiRequestFactory(OpenAiHttpProperties properties) {
        return openAiRequestFactory(properties, resolveProxy(properties));
    }

    static ClientHttpRequestFactory openAiRequestFactory(OpenAiHttpProperties properties, Proxy proxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());

        if (properties.getProxyMode() == OpenAiHttpProperties.ProxyMode.HTTP) {
            Proxy httpProxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort()));
            factory.setProxy(httpProxy);
            return factory;
        }
        if (properties.getProxyMode() == OpenAiHttpProperties.ProxyMode.SOCKS) {
            Proxy socksProxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort()));
            factory.setProxy(socksProxy);
            return factory;
        }
        if (properties.getProxyMode() == OpenAiHttpProperties.ProxyMode.DIRECT) {
            factory.setProxy(Proxy.NO_PROXY);
            return factory;
        }
        // AUTO: do not call setProxy — follow JVM http(s).proxy* / ProxySelector
        return factory;
    }

    public static Proxy resolveProxy(OpenAiHttpProperties properties) {
        return switch (properties.getProxyMode()) {
            case DIRECT -> Proxy.NO_PROXY;
            case HTTP -> new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort()));
            case SOCKS -> new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(properties.getProxyHost(), properties.getProxyPort()));
            case AUTO -> null;
        };
    }

    public static String describeEffectiveProxy(OpenAiHttpProperties properties) {
        return switch (properties.getProxyMode()) {
            case DIRECT -> "DIRECT";
            case HTTP -> "HTTP " + properties.getProxyHost() + ":" + properties.getProxyPort();
            case SOCKS -> "SOCKS " + properties.getProxyHost() + ":" + properties.getProxyPort();
            case AUTO -> buildAutoProxyDescription();
        };
    }

    public static String describeProxyInstance(Proxy proxy) {
        if (proxy == null) {
            return "AUTO (factory unset, JVM ProxySelector)";
        }
        if (proxy.type() == Proxy.Type.DIRECT) {
            return "DIRECT";
        }
        if (proxy.address() instanceof InetSocketAddress addr) {
            return proxy.type() + " " + addr.getHostString() + ":" + addr.getPort();
        }
        return proxy.type().name();
    }

    public static void logJvmSocksProxyWarningIfPresent(OpenAiHttpProperties.ProxyMode mode) {
        String socksHost = System.getProperty("socksProxyHost");
        String socksPort = System.getProperty("socksProxyPort");
        if (socksHost == null || socksHost.isBlank()) {
            return;
        }
        log.warn("JVM socksProxyHost={} socksProxyPort={} is set. "
                        + "This can cause OpenAI timeouts when app.openai.http.proxy-mode={}. "
                        + "Remove from IDEA Run Configuration VM options: -DsocksProxyHost / -DsocksProxyPort",
                socksHost, socksPort, mode);
    }

    private static String buildAutoProxyDescription() {
        StringBuilder sb = new StringBuilder("AUTO");
        sb.append(" | http.proxyHost=").append(System.getProperty("http.proxyHost"));
        sb.append(", https.proxyHost=").append(System.getProperty("https.proxyHost"));
        sb.append(", socksProxyHost=").append(System.getProperty("socksProxyHost"));
        sb.append(", socksProxyPort=").append(System.getProperty("socksProxyPort"));
        try {
            List<Proxy> proxies = ProxySelector.getDefault()
                    .select(URI.create("https://api.openai.com/v1/models"));
            sb.append(" | ProxySelector=").append(proxies);
        } catch (Exception e) {
            sb.append(" | ProxySelector error=").append(e.getMessage());
        }
        return sb.toString();
    }
}
