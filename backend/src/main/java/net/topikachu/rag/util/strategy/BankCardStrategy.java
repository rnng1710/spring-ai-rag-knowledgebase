package net.topikachu.rag.util.strategy;


import org.springframework.stereotype.Component;

@Component
public class BankCardStrategy implements PayStrategy{
    @Override
    public boolean pay(String orderId, Long amount) {
        System.out.println(String.format("银行卡支付成功！订单号：%s，金额：%s", orderId, amount));
        return true;
    }

    @Override
    public String getPayType() {
        return "BankCard";
    }
}
