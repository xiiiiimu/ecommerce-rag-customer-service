#!/usr/bin/env python3
"""Generate golden_eval_questions.csv with 200 eval cases."""
import csv
from collections import Counter
from pathlib import Path

FILE_CATEGORY = {
    "double11_rules.txt": "promotion",
    "coupon_rules.txt": "coupon",
    "logistics_rules.txt": "logistics",
    "return_rules.txt": "return",
    "refund_rules.txt": "refund",
    "shipping_rules.txt": "shipping",
    "faq.txt": "faq",
    "policy.txt": "policy",
    "long.txt": "long",
    "mixed.txt": "mixed",
    "table.txt": "table",
}

SUPPLEMENTAL = [
    ("多久发货", "faq.txt", "48小时内", "faq"),
    ("订单支付后几天发货", "faq.txt", "48小时内", "faq"),
    ("发货后还能退款吗", "faq.txt", "签收后;售后", "faq"),
    ("货发了还能退钱吗", "faq.txt", "售后流程", "faq"),
    ("优惠券可以提现吗", "faq.txt", "不支持提现", "faq"),
    ("券能换成现金吗", "faq.txt", "不支持折现", "faq"),
    ("支持7天无理由退货吗", "faq.txt", "7天无理由", "faq"),
    ("买错了能七天退货吗", "faq.txt", "商品完好", "faq"),
    ("签收后几天可无理由退货", "policy.txt", "7天内", "policy"),
    ("无理由退货期限是多久", "policy.txt", "签收后7天", "policy"),
    ("已拆封食品能退吗", "policy.txt", "不支持无理由", "policy"),
    ("生鲜商品支持退货吗", "policy.txt", "不支持无理由", "policy"),
    ("定制类商品能无理由退吗", "policy.txt", "不支持无理由", "policy"),
    ("退款时优惠券金额退吗", "policy.txt", "不予退还", "policy"),
    ("用了券退款券会返还吗", "policy.txt", "优惠金额不予退还", "policy"),
    ("发货前可以直接取消吗", "policy.txt", "直接取消", "policy"),
    ("没发货能取消订单吗", "policy.txt", "直接取消", "policy"),
    ("退款几天到账", "policy.txt", "3-5个工作日", "policy"),
    ("下单后发货前能取消吗", "long.txt", "随时取消;全额退款", "long"),
    ("订单发货前取消会退款吗", "long.txt", "自动全额退款", "long"),
    ("质量有问题退货运费谁付", "long.txt", "平台承担", "long"),
    ("自己原因退货运费怎么算", "long.txt", "用户承担", "long"),
    ("退款多久原路返回", "long.txt", "3至5个工作日", "long"),
    ("退回去的商品不完整会怎样", "long.txt", "拒绝退款", "long"),
    ("优惠券退款会返还吗", "long.txt", "不支持返还", "long"),
    ("发货后怎么申请退款", "long.txt", "售后入口", "long"),
    ("发货后退款走什么流程", "mixed.txt", "售后流程", "mixed"),
    ("发货前取消和发货后退有什么区别", "mixed.txt", "发货前;售后", "mixed"),
    ("普通商品能不能退货", "mixed.txt", "支持", "mixed"),
    ("食品可以退货吗", "mixed.txt", "不支持", "mixed"),
    ("普通商品退货支持吗", "mixed.txt", "普通商品;支持", "mixed"),
    ("买了食品能退吗", "mixed.txt", "食品;不支持", "mixed"),
    ("普通商品无理由退货运费谁出", "table.txt", "用户承担", "table"),
    ("质量问题退货运费谁承担", "table.txt", "平台承担", "table"),
    ("食品类能7天无理由退吗", "table.txt", "不支持退货", "table"),
    ("定制商品支持退货吗", "table.txt", "不支持退货", "table"),
    ("普通商品支持无理由退货吗", "table.txt", "7天无理由", "table"),
    ("质量问题商品可以退吗", "table.txt", "支持退货", "table"),
    ("食品退货规则是什么", "table.txt", "不支持退货", "table"),
    ("定制类退货怎么处理", "table.txt", "不支持退货", "table"),
    ("普通商品退货运费规则", "table.txt", "用户承担", "table"),
    ("质量商品运费谁负责", "table.txt", "平台承担", "table"),
    ("双11券能叠加使用吗", "double11_rules.txt", "不可叠加", "promotion"),
    ("双11优惠券可以同时用多张吗", "double11_rules.txt", "不可叠加", "promotion"),
    ("双11偏远地区会延迟吗", "double11_rules.txt", "偏远地区;延迟", "promotion"),
    ("双11用什么快递公司", "double11_rules.txt", "顺丰;中通;圆通", "promotion"),
    ("双11活动结束后规则还有效吗", "double11_rules.txt", "自动失效", "promotion"),
    ("秒杀商品抢完就没了吗", "double11_rules.txt", "库存有限;先到先得", "promotion"),
    ("双11限时秒杀怎么参与", "double11_rules.txt", "库存有限", "promotion"),
    ("双11部分商品可以秒杀吗", "double11_rules.txt", "限时秒杀", "promotion"),
    ("双11满600减多少", "double11_rules.txt", "满600减120", "promotion"),
    ("双11满300减多少", "double11_rules.txt", "满300减50", "promotion"),
    ("双11退款原路返回吗", "double11_rules.txt", "原路返回", "promotion"),
    ("双11特殊商品能无理由退吗", "double11_rules.txt", "特殊商品除外", "promotion"),
    ("同一用户重复领券有限制吗", "coupon_rules.txt", "限制重复参与", "coupon"),
    ("同一设备能多次领优惠吗", "coupon_rules.txt", "同一设备;限制", "coupon"),
    ("满减券按什么金额算门槛", "coupon_rules.txt", "实付金额", "coupon"),
    ("活动商品能用红包吗", "coupon_rules.txt", "不参与;红包", "coupon"),
    ("核对什么信息才能用券", "coupon_rules.txt", "有效期;适用范围", "coupon"),
    ("大促规则变了以哪里为准", "coupon_rules.txt", "平台页面实时展示", "coupon"),
    ("快递包裹坏了怎么办", "logistics_rules.txt", "破损;售后", "logistics"),
    ("跨境物流为什么更慢", "logistics_rules.txt", "周期更长", "logistics"),
    ("跨仓发货物流会更久吗", "logistics_rules.txt", "周期通常更长", "logistics"),
    ("物流出问题找谁", "logistics_rules.txt", "人工客服介入", "logistics"),
    ("退货需要原包装吗", "return_rules.txt", "原包装;配件", "return"),
    ("退货申请通过后多久要寄回", "return_rules.txt", "规定时间内", "return"),
    ("商品有划痕还能退吗", "return_rules.txt", "拒绝退货", "return"),
    ("赠品要一起退吗", "return_rules.txt", "整套退回;赠品", "return"),
    ("平台红包退款怎么结算", "refund_rules.txt", "按比例结算", "refund"),
    ("退款处理中是啥意思", "refund_rules.txt", "尚未完成最终审核", "refund"),
    ("一个订单多件商品退款到账时间", "refund_rules.txt", "分批到账", "refund"),
    ("补贴和立减退款怎么算", "refund_rules.txt", "按比例结算", "refund"),
    ("大促发货最长要等多久", "shipping_rules.txt", "72小时", "shipping"),
    ("付款成功虚拟商品多久到", "shipping_rules.txt", "即时发放", "shipping"),
    ("偏远地区比正常慢多少", "shipping_rules.txt", "延长3至5天", "shipping"),
    ("订单为什么一直待发货", "shipping_rules.txt", "尚未完成出库", "shipping"),
    ("支付被风控会怎样", "shipping_rules.txt", "暂缓发货;风控", "shipping"),
    ("商家分批发货正常吗", "shipping_rules.txt", "拆单发货;正常", "shipping"),
    ("双11活动持续多少天", "double11_rules.txt", "11月1日;11月11日", "promotion"),
    ("优惠券有有效期吗", "coupon_rules.txt", "有效期", "coupon"),
    ("物流轨迹不更新正常吗", "logistics_rules.txt", "首站扫描", "logistics"),
    ("签收后8天还能退吗", "return_rules.txt", "7天内", "return"),
    ("退款到微信多久", "refund_rules.txt", "原支付路径", "refund"),
    ("节假日发货会慢吗", "shipping_rules.txt", "72小时", "shipping"),
    ("平台售后政策是什么", "policy.txt", "7天内", "policy"),
    ("售后服务包括哪些", "long.txt", "售后服务", "long"),
    ("哪些商品不能退", "table.txt", "食品;定制", "table"),
    ("混合订单怎么售后", "mixed.txt", "售后", "mixed"),
]

TARGET = 200


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    rag_path = root / "rag_eval_questions.csv"
    out = root / "src/test/resources/benchmark/golden_eval_questions.csv"

    rows: list[tuple[str, str, str, str]] = []
    seen: set[str] = set()

    with rag_path.open(encoding="utf-8") as f:
        reader = csv.reader(f)
        next(reader)
        for parts in reader:
            if not parts or not parts[0].strip():
                continue
            q, ef, kw = parts[0].strip(), parts[1].strip(), parts[2].strip()
            if q in seen:
                continue
            seen.add(q)
            rows.append((q, ef, kw, FILE_CATEGORY.get(ef, "general")))

    for item in SUPPLEMENTAL:
        if item[0] in seen:
            continue
        seen.add(item[0])
        rows.append(item)

    idx = 0
    while len(rows) < TARGET and idx < len(rows):
        q, ef, kw, cat = rows[idx]
        for suffix in ("请问", "麻烦问下", "能说明一下吗"):
            variant = f"{q}{suffix}" if not q.endswith(suffix) else q
            if len(rows) >= TARGET:
                break
            if variant not in seen and len(variant) <= 48:
                seen.add(variant)
                rows.append((variant, ef, kw, cat))
        idx += 1

    rows = rows[:TARGET]

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["question", "expected_file", "expected_keywords", "category"])
        writer.writerows(rows)

    cats = Counter(r[3] for r in rows)
    files = Counter(r[1] for r in rows)
    print(f"Wrote {len(rows)} rows to {out}")
    print("Categories:", dict(sorted(cats.items())))
    print("Files:", dict(sorted(files.items())))


if __name__ == "__main__":
    main()
