package net.topikachu.rag.util.strategy;

import org.springframework.stereotype.Component;

@Component
public class WechatStrategy implements PayStrategy {

    @Override
    public boolean pay(String orderId, Long amount) {
        System.out.printf("微信支付成功!订单号：%S，金额：%s%n", orderId, amount);
        return true;
    }

    @Override
    public String getPayType() {
        return "Wechat";
    }
}
