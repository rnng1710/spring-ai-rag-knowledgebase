package net.topikachu.rag.util.strategy;

import org.springframework.stereotype.Component;

@Component
public class AlipayStrategy implements PayStrategy{

    @Override
    public boolean pay(String orderId, Long amount) {
        System.out.printf("支付宝支付成功，订单号：%S，金额：%s%n", orderId, amount);
        return true;
    }

    @Override
    public String getPayType(){
        return "alipay";
    }
}
