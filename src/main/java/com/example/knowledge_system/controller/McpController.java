package com.example.knowledge_system.controller;

import com.example.knowledge_system.dto.McpToolCallRequest;
import com.example.knowledge_system.dto.McpToolCallResponse;
import com.example.knowledge_system.service.KnowledgeBaseMcpToolService;
import com.example.knowledge_system.util.AuthUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/mcp")
public class McpController {

    private final KnowledgeBaseMcpToolService toolService;

    public McpController(KnowledgeBaseMcpToolService toolService) {
        this.toolService = toolService;
    }

    @GetMapping("/tools/list")
    public List<Map<String, Object>> listTools() {
        return List.of(
                tool(
                        "kb_list_files",
                        "查询知识库文件列表",
                        Map.of()
                ),
                tool(
                        "kb_delete_file",
                        "删除知识库文件",
                        Map.of(
                                "fileName", Map.of(
                                        "type", "string",
                                        "description", "文件名，例如 faq.txt"
                                )
                        )
                ),
                tool(
                        "kb_list_versions",
                        "查询指定文件的版本记录",
                        Map.of(
                                "fileName", Map.of(
                                        "type", "string",
                                        "description", "文件名，例如 faq.txt"
                                )
                        )
                ),
                tool(
                        "kb_query_upload_task",
                        "查询上传任务状态",
                        Map.of(
                                "taskId", Map.of(
                                        "type", "string",
                                        "description", "上传任务 ID"
                                )
                        )
                ),
                tool(
                        "kb_retry_upload_task",
                        "重试 FAILED 或 DEAD 状态的上传任务",
                        Map.of(
                                "taskId", Map.of(
                                        "type", "string",
                                        "description", "上传任务 ID"
                                )
                        )
                )
        );
    }

    @PostMapping("/tools/call")
    public McpToolCallResponse callTool(@RequestBody McpToolCallRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            AuthUtil.requireAdmin(httpRequest);
            if (request == null || request.getName() == null || request.getName().isBlank()) {
                return new McpToolCallResponse("缺少工具名称 name", true);
            }

            Map<String, Object> args = request.getArguments() == null
                    ? Collections.emptyMap()
                    : request.getArguments();

            Object result = switch (request.getName()) {
                case "kb_list_files" -> toolService.listFiles();

                case "kb_delete_file" -> {
                    String fileName = getStringArg(args, "fileName");
                    yield toolService.deleteFile(fileName);
                }

                case "kb_list_versions" -> {
                    String fileName = getStringArg(args, "fileName");
                    yield toolService.listVersions(fileName);
                }

                case "kb_query_upload_task" -> {
                    String taskId = getStringArg(args, "taskId");
                    yield toolService.queryUploadTask(taskId);
                }

                case "kb_retry_upload_task" -> {
                    String taskId = getStringArg(args, "taskId");
                    yield toolService.retryUploadTask(taskId);
                }

                default -> throw new RuntimeException("未知 MCP 工具: " + request.getName());
            };

            return new McpToolCallResponse(result, false);

        } catch (Exception e) {
            return new McpToolCallResponse(e.getMessage(), true);
        }
    }

    private String getStringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new RuntimeException("缺少参数: " + key);
        }
        return String.valueOf(value).trim();
    }

    private Map<String, Object> tool(String name,
                                     String description,
                                     Map<String, Object> properties) {
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", properties,
                        "required", new ArrayList<>(properties.keySet())
                )
        );
    }
}