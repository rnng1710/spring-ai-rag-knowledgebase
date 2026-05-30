package net.topikachu.rag.util.strategy;

import org.springframework.stereotype.Component;

@Component
public class BalanceStrategy implements PayStrategy {

    @Override
    public String getPayType() {
        return "Balance";
    }

    @Override
    public boolean pay(String orderId, Long amount) {

        System.out.printf("余额支付成功！，订单Id：%S, 金额： %s%n" , orderId,amount);
        return true;
    }
}
