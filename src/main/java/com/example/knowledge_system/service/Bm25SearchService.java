package com.example.knowledge_system.service;

import com.example.knowledge_system.dto.DocumentChunkVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class Bm25SearchService {

    private static final String INDEX = "knowledge_chunks";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Bm25SearchService(RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    public void initIndex() throws IOException {
        Request exists = new Request("HEAD", "/" + INDEX);
        Response existsResponse;

        try {
            existsResponse = restClient.performRequest(exists);
            if (existsResponse.getStatusLine().getStatusCode() == 200) {
                return;
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> body = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "fileName", Map.of("type", "keyword"),
                                "content", Map.of("type", "text"),
                                "chunkIndex", Map.of("type", "integer"),
                                "docType", Map.of("type", "keyword"),
                                "docId", Map.of("type", "keyword"),
                                "chunkId", Map.of("type", "keyword"),
                                "version", Map.of("type", "integer"),
                                "status", Map.of("type", "keyword"),
                                "startTime", Map.of("type", "date"),
                                "endTime", Map.of("type", "date")

                        )
                )
        );

        Request create = new Request("PUT", "/" + INDEX);
        create.setJsonEntity(objectMapper.writeValueAsString(body));
        restClient.performRequest(create);
    }

    public void indexChunk(Long id,
                           String fileName,
                           int chunkIndex,
                           String content,
                           String docType,
                           String status,
                           Object startTime,
                           Object endTime,
                           String docId,
                           String chunkId,
                           Integer version){
        try {
            String esDocId = chunkId != null ? chunkId : md5(fileName + "_" + version + "_" + chunkIndex + "_" + content);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("fileName", fileName);
            doc.put("chunkIndex", chunkIndex);
            doc.put("content", content);
            doc.put("docType", docType);
            doc.put("status", status);
            doc.put("id", id);
            doc.put("docId", docId);
            doc.put("chunkId", chunkId);
            doc.put("version", version);

            if (startTime != null) {
                doc.put("startTime", startTime.toString());
            }
            if (endTime != null) {
                doc.put("endTime", endTime.toString());
            }

            Request request = new Request("PUT", "/" + INDEX + "/_doc/" + esDocId);
            request.setJsonEntity(objectMapper.writeValueAsString(doc));
            restClient.performRequest(request);
        } catch (Exception e) {
            // BM25 索引失败不应该影响主库入库
            e.printStackTrace();
        }
    }

    public void deleteByFileName(String fileName) {
        try {
            Map<String, Object> body = Map.of(
                    "query", Map.of(
                            "term", Map.of("fileName", fileName)
                    )
            );

            Request request = new Request("POST", "/" + INDEX + "/_delete_by_query");
            request.setJsonEntity(objectMapper.writeValueAsString(body));
            restClient.performRequest(request);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void deleteByDocId(String docId) {
        try {
            Map<String, Object> body = Map.of(
                    "query", Map.of(
                            "term", Map.of("docId", docId)
                    )
            );

            Request request = new Request("POST", "/" + INDEX + "/_delete_by_query");
            request.setJsonEntity(objectMapper.writeValueAsString(body));
            restClient.performRequest(request);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public List<DocumentChunkVO> search(String query, int limit) {
        try {
            Map<String, Object> body = Map.of(
                    "size", limit,
                    "query", Map.of(
                            "bool", Map.of(
                                    "must", List.of(
                                            Map.of("match", Map.of("content", query))
                                    ),
                                    "filter", List.of(
                                            Map.of("term", Map.of("status", "ACTIVE")),

                                            Map.of("bool", Map.of(
                                                    "should", List.of(
                                                            Map.of("bool", Map.of(
                                                                    "must_not", Map.of("exists", Map.of("field", "startTime"))
                                                            )),
                                                            Map.of("range", Map.of(
                                                                    "startTime", Map.of("lte", "now")
                                                            ))
                                                    ),
                                                    "minimum_should_match", 1
                                            )),

                                            Map.of("bool", Map.of(
                                                    "should", List.of(
                                                            Map.of("bool", Map.of(
                                                                    "must_not", Map.of("exists", Map.of("field", "endTime"))
                                                            )),
                                                            Map.of("range", Map.of(
                                                                    "endTime", Map.of("gte", "now")
                                                            ))
                                                    ),
                                                    "minimum_should_match", 1
                                            ))
                                    )
                            )
                    )
            );

            Request request = new Request("GET", "/" + INDEX + "/_search");
            request.setJsonEntity(objectMapper.writeValueAsString(body));

            Response response = restClient.performRequest(request);
            JsonNode root = objectMapper.readTree(response.getEntity().getContent());
            JsonNode hits = root.path("hits").path("hits");

            List<DocumentChunkVO> result = new ArrayList<>();

            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");

                DocumentChunkVO vo = new DocumentChunkVO();

                vo.setId(source.path("id").isMissingNode() ? null : source.path("id").asLong());
                vo.setFileName(source.path("fileName").asText());
                vo.setContent(source.path("content").asText());
                vo.setChunkIndex(source.path("chunkIndex").asInt());
                vo.setDocType(source.path("docType").asText(null));
                vo.setScore(hit.path("_score").asDouble());
                vo.setDocId(source.path("docId").asText(null));
                vo.setChunkId(source.path("chunkId").asText(null));

                if (!source.path("version").isMissingNode() && !source.path("version").isNull()) {
                    vo.setVersion(source.path("version").asInt());
                }

                vo.setStatus(source.path("status").asText(null));
                vo.setStartTime(parseDateTime(source.path("startTime").asText(null)));
                vo.setEndTime(parseDateTime(source.path("endTime").asText(null)));

                result.add(vo);
            }

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private String md5(String text) {
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
