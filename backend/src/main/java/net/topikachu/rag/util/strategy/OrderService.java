package net.topikachu.rag.util.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    public void payOrder(String payType, String orderId, Long amount){
        PayMentStrategyFactory payMentStrategyFactory = new PayMentStrategyFactory();
        PayStrategy payStrategy = payMentStrategyFactory.getPayStrategy(payType);
        boolean pay = payStrategy.pay(orderId, amount);
        if(pay) {
            System.out.println("订单处理中~~~");
        } else {
            System.out.println("订单处理失败");
        }
    }
}
