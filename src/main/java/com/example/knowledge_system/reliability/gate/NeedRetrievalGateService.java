package com.example.knowledge_system.reliability.gate;

import com.example.knowledge_system.reliability.dto.RetrievalGateResult;
import com.example.knowledge_system.reliability.dto.RoutingDecision;
import org.springframework.stereotype.Service;

@Service
public class NeedRetrievalGateService {

    public RetrievalGateResult evaluate(RoutingDecision routing, String question) {
        RetrievalGateResult result = new RetrievalGateResult();

        if (routing == null || !routing.isNeedRetrieval()) {
            result.setShouldRetrieve(false);
            result.setRetrievalValue(0.0);
            result.setReason("router marked need_retrieval=false");
            return result;
        }

        String path = routing.getSuggestedPath();
        if ("rag".equals(path)
                || "order_with_rag".equals(path)
                || "rag_with_tool".equals(path)) {
            result.setShouldRetrieve(true);
            result.setRetrievalValue(0.85);
            result.setReason("path requires retrieval: " + path);
            return result;
        }

        result.setShouldRetrieve(false);
        result.setRetrievalValue(0.2);
        result.setReason("path does not require retrieval: " + routing.getSuggestedPath());
        return result;
    }
}
