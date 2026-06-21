package com.example.knowledge_system.benchmark.e2e;

import java.util.List;

public final class E2EQuestionSuite {

    private E2EQuestionSuite() {
    }

    public static List<E2EQuestionCase> standardCases(String sampleOrderNo) {
        return List.of(
                new E2EQuestionCase("FAQ", "支持7天无理由退货吗", "knowledge_query", "7天无理由", "faq.txt"),
                new E2EQuestionCase("Logistics", "物流信息多久更新", "knowledge_query", "24小时", "logistics_rules.txt"),
                new E2EQuestionCase("Refund", "退款申请多久审核", "knowledge_query", "审核", "refund_rules.txt"),
                new E2EQuestionCase("Coupon", "优惠券通常有什么限制", "knowledge_query", "门槛", "coupon_rules.txt"),
                new E2EQuestionCase("Order", "查询订单 " + sampleOrderNo, "order", "订单", null)
        );
    }

    public static List<String> multiTurnScript(String sampleOrderNo) {
        return List.of(
                "我想了解订单 " + sampleOrderNo + " 的物流情况",
                "如果这个订单想退款，需要满足什么条件？",
                "退款一般多久到账？"
        );
    }
}
