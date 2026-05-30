package net.topikachu.rag.util.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;

// 演示用策略模式示例，不需要注册为 Bean
public class Order implements CommandLineRunner {

    @Autowired
    private OrderService orderService;

    @Override
    public void run(String[] args) {
        orderService.payOrder("BankCard", "999", 123L);
    }
}
