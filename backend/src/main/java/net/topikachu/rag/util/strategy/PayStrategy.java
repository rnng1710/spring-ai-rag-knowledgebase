package net.topikachu.rag.util.strategy;

public interface PayStrategy {

    String getPayType();

    boolean pay(String orderId, Long amount);
}
