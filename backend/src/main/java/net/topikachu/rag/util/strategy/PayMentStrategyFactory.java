package net.topikachu.rag.util.strategy;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PayMentStrategyFactory {

    @Autowired
    private List<PayStrategy> payStrategies;

    private Map<String, PayStrategy> strategyMap = new HashMap<>();

    @PostConstruct
    public void init(){
        for(PayStrategy payStrategy: payStrategies){
            strategyMap.put(payStrategy.getPayType(),payStrategy);
        }
    }

    public PayStrategy getPayStrategy(String payType){
        PayStrategy payStrategy = strategyMap.get(payType);
        if(payStrategy == null){
            throw new IllegalArgumentException("Didn't find PayStrategy for PayType:"+payType);
        }
        return payStrategy;
    }
}
