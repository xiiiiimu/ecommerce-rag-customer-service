package com.example.knowledge_system.controller;

import com.example.knowledge_system.dto.*;
import com.example.knowledge_system.entity.DocumentChunkVector;
import com.example.knowledge_system.service.AsyncTaskService;
import com.example.knowledge_system.service.UploadTaskService;
import com.example.knowledge_system.service.VectorService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.example.knowledge_system.dto.AsyncUploadResponse;
import com.example.knowledge_system.dto.OrderQueryResult;
import com.example.knowledge_system.dto.KafkaUploadMessage;
import com.example.knowledge_system.service.KafkaProducerService;
import com.example.knowledge_system.entity.CustomerOrder;
import com.example.knowledge_system.entity.UploadTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import java.io.File;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import java.io.File;
import java.time.LocalDate;


import java.util.List;

@RestController
@RequestMapping("/vector")
public class VectorController {
    private final VectorService vectorService;
    private final AsyncTaskService asyncTaskService;
    private final KafkaProducerService kafkaProducerService;
    private final UploadTaskService uploadTaskService;
    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    public VectorController(VectorService vectorService, AsyncTaskService asyncTaskService, KafkaProducerService kafkaProducerService, UploadTaskService uploadTaskService){
        this.vectorService = vectorService;
        this.asyncTaskService = asyncTaskService;
        this.kafkaProducerService = kafkaProducerService;
        this.uploadTaskService = uploadTaskService;

    }

    @PostMapping("/ask")
    public String ask(@RequestBody String question){
        return vectorService.ask(question);
    }

    @PostMapping("/save")
    public String save(@RequestBody String text){
        vectorService.save(text);
        return "ok";
    }
    @PostMapping("/search")
    public List<DocumentChunkVO> search(@RequestBody String query){
        return vectorService.search(query);
    }

    @PostMapping("/upload")
    public UploadResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime
    ) throws IOException {
        return vectorService.uploadTxtFile(file, docType, status, startTime, endTime);
    }

    @PostMapping("/upload-multi")
    public MultiUploadResult uploadMulti(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime
    ) throws IOException {
        System.out.println("upload-multi called");
        System.out.println("files = " + (files == null ? "null" : files.length));
        System.out.println("docType = " + docType);
        System.out.println("status = " + status);
        System.out.println("startTime = " + startTime);
        System.out.println("endTime = " + endTime);

        return vectorService.uploadTxtFiles(files, docType, status, startTime, endTime);
    }

    @PostMapping("/deleteByFile")
    public DeleteFileResult deleteFileResult(@RequestBody String fileName){
        return vectorService.deleteByFileName(fileName.trim());
    }

    @PostMapping("/rollback")
    public String rollback(@RequestParam("docId") String docId,
                           @RequestParam("version") Integer version) {
        return vectorService.rollbackDocument(docId, version);
    }

    @PostMapping("/ask-memory")
    @Deprecated(since = "reliability-orchestrator", forRemoval = false)
    public MemoryAskResponse askWithMemory(@RequestBody AskRequest request) {
        // Legacy endpoint: kept for compatibility/testing only.
        // Main production path should use /chat/ask -> RagOrchestrator.
        String answer = vectorService.askWithMemory(
                request.getSessionId(),
                request.getQuestion()
        ).getAnswer();
        return new MemoryAskResponse(answer, request.getSessionId());
    }

    @PostMapping("/upload-async")
    public AsyncUploadResponse uploadAsync(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime
    ){
        String taskId = UUID.randomUUID().toString();

        asyncTaskService.processUpload(taskId, files, docType, status, startTime, endTime);

        return new AsyncUploadResponse(taskId, "文件已提交，正在后台处理");
    }

    @PostMapping("/upload-kafka")
    public List<AsyncUploadResponse> uploadKafka(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "docType", required = false) String docType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime
    ) throws IOException {

        List<AsyncUploadResponse> result = new ArrayList<>();

        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }

                String taskId = UUID.randomUUID().toString();

                String dateDir = LocalDate.now().toString();

                File baseDir = new File(uploadDir).getAbsoluteFile();
                File dir = new File(baseDir, dateDir);

                if (!dir.exists()) {
                    boolean created = dir.mkdirs();
                    if (!created) {
                        throw new RuntimeException("创建上传目录失败: " + dir.getAbsolutePath());
                    }
                }

                System.out.println("上传目录: " + dir.getAbsolutePath());

                String originalFileName = file.getOriginalFilename();
                String storedFileName = taskId + "-" + originalFileName;
                File dest = new File(dir, storedFileName);

                file.transferTo(dest);
                String filePath = dest.getAbsolutePath();

                uploadTaskService.createTask(
                        taskId,
                        originalFileName,
                        filePath,
                        docType,
                        status
                );

                KafkaUploadMessage message = new KafkaUploadMessage();
                message.setTaskId(taskId);

                kafkaProducerService.sendUploadMessage(message);

                result.add(new AsyncUploadResponse(taskId, "文件已保存并提交到 Kafka，正在后台消费处理"));
            }
        }

        return result;
    }

    @GetMapping("/order/byUserId")
    public List<CustomerOrder> getOrdersByUserId(@RequestParam("userId") Long userId) {
        return vectorService.listOrdersByUserId(userId);
    }

    @GetMapping("/order/byOrderNo")
    public CustomerOrder getOrderByOrderNo(@RequestParam("orderNo") String orderNo) {
        return vectorService.getOrderByOrderNo(orderNo);
    }

    @GetMapping("/order/query")
    public OrderQueryResult queryOrderTool(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "orderNo", required = false) String orderNo
    ) {
        return vectorService.queryOrder(userId, orderNo);
    }

    @GetMapping("/task/status")
    public UploadTask getTaskStatus(@RequestParam("taskId") String taskId) {
        return uploadTaskService.getByTaskId(taskId);
    }
    @GetMapping("/versions")
    public List<Map<String, Object>> listVersions(@RequestParam("fileName") String fileName) {
        return vectorService.listVersionsByFileName(fileName);
    }
    @PostMapping("/task/retry")
    public String retryTask(@RequestParam("taskId") String taskId) {

        UploadTask task = uploadTaskService.getByTaskId(taskId);
        if (task == null) {
            return "任务不存在";
        }

        if (!uploadTaskService.canRetry(taskId)) {
            return "当前状态不允许重试: " + task.getStatus();
        }

        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            return "任务文件路径为空，无法重试";
        }

        uploadTaskService.resetToPending(taskId);

        KafkaUploadMessage message = new KafkaUploadMessage();
        message.setTaskId(taskId);

        kafkaProducerService.sendUploadMessage(message);

        return "任务已重新投递: " + taskId;
    }
}
