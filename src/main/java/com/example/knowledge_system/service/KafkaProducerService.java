package com.example.knowledge_system.service;
import com.example.knowledge_system.dto.KafkaUploadMessage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {
    private static final String TOPIC = "knowledge-upload-topic";
    private static final String DLQ_TOPIC = "knowledge-upload-dlq";
    private final KafkaTemplate<String, KafkaUploadMessage> kafkaTemplate;

    public KafkaProducerService(KafkaTemplate<String, KafkaUploadMessage> kafkaTemplate){
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendUploadMessage(KafkaUploadMessage message){
        kafkaTemplate.send(TOPIC, message.getTaskId(), message);
    }

    public void sendDlqMessage(KafkaUploadMessage message) {
        kafkaTemplate.send(DLQ_TOPIC, message.getTaskId(), message);
    }
}
