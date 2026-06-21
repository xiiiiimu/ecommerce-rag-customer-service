package com.example.knowledge_system.health;

import com.example.knowledge_system.config.OpenAiHttpProperties;
import com.example.knowledge_system.config.OpenAiRestClientConfig;
import com.example.knowledge_system.util.LlmExceptionDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionProperties;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.Proxy;

@Slf4j
@Component
public class OpenAiHealthChecker implements ApplicationRunner {

    private final OpenAiConnectionProperties connectionProperties;
    private final OpenAiChatProperties chatProperties;
    private final OllamaConnectionProperties ollamaConnectionProperties;
    private final OllamaEmbeddingProperties ollamaEmbeddingProperties;
    private final OpenAiHttpProperties httpProperties;
    private final RestClient.Builder restClientBuilder;

    public OpenAiHealthChecker(OpenAiConnectionProperties connectionProperties,
                               OpenAiChatProperties chatProperties,
                               OllamaConnectionProperties ollamaConnectionProperties,
                               OllamaEmbeddingProperties ollamaEmbeddingProperties,
                               OpenAiHttpProperties httpProperties,
                               RestClient.Builder restClientBuilder) {
        this.connectionProperties = connectionProperties;
        this.chatProperties = chatProperties;
        this.ollamaConnectionProperties = ollamaConnectionProperties;
        this.ollamaEmbeddingProperties = ollamaEmbeddingProperties;
        this.httpProperties = httpProperties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String baseUrl = connectionProperties.getBaseUrl();
        String modelsUrl = normalizeModelsUrl(baseUrl);
        boolean apiKeyPresent = connectionProperties.getApiKey() != null
                && !connectionProperties.getApiKey().isBlank();
        Proxy resolvedProxy = OpenAiRestClientConfig.resolveProxy(httpProperties);

        log.info("========== AI startup health check ==========");
        log.info("llmProvider=DeepSeek (OpenAI-compatible client)");
        log.info("llmBaseUrl={}", baseUrl);
        log.info("llmModelsUrl={}", modelsUrl);
        log.info("llmModel={}", chatProperties.getOptions().getModel());
        log.info("embeddingProvider=Ollama");
        log.info("ollamaBaseUrl={}", ollamaConnectionProperties.getBaseUrl());
        log.info("embeddingModel={}", ollamaEmbeddingProperties.getOptions().getModel());
        log.info("llmApiKeyPresent={}", apiKeyPresent);
        log.info("proxyMode={}", httpProperties.getProxyMode());
        log.info("proxyHost={}", httpProperties.getProxyHost());
        log.info("proxyPort={}", httpProperties.getProxyPort());
        log.info("actualProxyType={}", resolvedProxy == null ? "AUTO" : resolvedProxy.type());
        log.info("httpClientProxy={}", OpenAiRestClientConfig.describeEffectiveProxy(httpProperties));
        log.info("connectTimeout={}, readTimeout={}",
                httpProperties.getConnectTimeout(), httpProperties.getReadTimeout());
        OpenAiRestClientConfig.logJvmSocksProxyWarningIfPresent(httpProperties.getProxyMode());

        if (!apiKeyPresent) {
            log.error("DeepSeek api-key is missing. Set DEEPSEEK_API_KEY or spring.ai.openai.api-key.");
            log.info("=================================================");
            return;
        }

        checkDeepSeekConnectivity(modelsUrl);
        checkOllamaConnectivity();
        log.info("=================================================");
    }

    private void checkDeepSeekConnectivity(String modelsUrl) {
        try {
            long start = System.currentTimeMillis();
            RestClient client = restClientBuilder.build();
            String body = client.get()
                    .uri(modelsUrl)
                    .header("Authorization", "Bearer " + connectionProperties.getApiKey())
                    .retrieve()
                    .body(String.class);
            long cost = System.currentTimeMillis() - start;
            int previewLen = body == null ? 0 : Math.min(body.length(), 120);
            String preview = body == null ? "" : body.substring(0, previewLen);
            log.info("DeepSeek connectivity OK, costMs={}, responsePreview={}", cost, preview);
            log.info("METRIC deepSeekHealthCheck=1, status=ok, costMs={}", cost);
        } catch (Exception e) {
            Throwable root = LlmExceptionDiagnostics.rootCause(e);
            log.error("DeepSeek connectivity FAILED. {}", LlmExceptionDiagnostics.format(e));
            log.error("DeepSeek rootCause: {} - {}",
                    root == null ? "unknown" : root.getClass().getName(),
                    root == null ? "" : root.getMessage());
            log.info("METRIC deepSeekHealthCheck=1, status=fail, rootCause={}",
                    root == null ? "unknown" : root.getClass().getSimpleName());
        }
    }

    private void checkOllamaConnectivity() {
        String ollamaBase = ollamaConnectionProperties.getBaseUrl();
        if (ollamaBase == null || ollamaBase.isBlank()) {
            ollamaBase = "http://localhost:11434";
        }
        String tagsUrl = ollamaBase.endsWith("/") ? ollamaBase + "api/tags" : ollamaBase + "/api/tags";
        try {
            long start = System.currentTimeMillis();
            RestClient client = restClientBuilder.build();
            String body = client.get().uri(tagsUrl).retrieve().body(String.class);
            long cost = System.currentTimeMillis() - start;
            int previewLen = body == null ? 0 : Math.min(body.length(), 120);
            String preview = body == null ? "" : body.substring(0, previewLen);
            log.info("Ollama connectivity OK, costMs={}, responsePreview={}", cost, preview);
            log.info("METRIC ollamaHealthCheck=1, status=ok, costMs={}", cost);
        } catch (Exception e) {
            Throwable root = LlmExceptionDiagnostics.rootCause(e);
            log.error("Ollama connectivity FAILED. url={} {}", tagsUrl, LlmExceptionDiagnostics.format(e));
            log.info("METRIC ollamaHealthCheck=1, status=fail, rootCause={}",
                    root == null ? "unknown" : root.getClass().getSimpleName());
            log.warn("Hint: start Ollama and pull embedding model, e.g. ollama pull nomic-embed-text");
        }
    }

    private String normalizeModelsUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.deepseek.com/v1/models";
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/v1")) {
            return trimmed + "/models";
        }
        if (trimmed.contains("/chat/completions")) {
            log.warn("spring.ai.openai.base-url looks like a full endpoint path, expected host base only. value={}", baseUrl);
            return "https://api.deepseek.com/v1/models";
        }
        return trimmed + "/v1/models";
    }
}
