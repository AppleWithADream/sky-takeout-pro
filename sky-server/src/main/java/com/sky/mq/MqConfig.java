package com.sky.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单超时关单 —— MQ 配置
 * 延迟消息 = TTL + 死信交换机：
 */
@Configuration
public class MqConfig {

    /** 1. 延迟交换机 */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(MqConstants.ORDER_DELAY_EXCHANGE, true, false);
    }

    /**
     * 2. 延迟队列：TTL 15 分钟 + 到期进死信
     */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_DELAY_QUEUE)
                .ttl(15 * 60 * 1000)                                         // 15 分钟（毫秒）
                .deadLetterExchange(MqConstants.ORDER_DLX_EXCHANGE)          // 到期后进哪个交换机
                .deadLetterRoutingKey(MqConstants.ORDER_TIMEOUT_ROUTING_KEY) // 用什么路由键
                .build();
    }

    /** 3. 绑定：延迟交换机 → 延迟队列 */
    @Bean
    public Binding orderDelayBinding(Queue orderDelayQueue, DirectExchange orderDelayExchange) {
        return BindingBuilder.bind(orderDelayQueue)
                .to(orderDelayExchange)
                .with(MqConstants.ORDER_DELAY_ROUTING_KEY);
    }

    /** 4. 死信交换机 */
    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(MqConstants.ORDER_DLX_EXCHANGE, true, false);
    }

    /** 5. 超时处理队列 */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE).build();
    }

    /** 6. 绑定：死信交换机 → 超时处理队列 */
    @Bean
    public Binding orderTimeoutBinding(Queue orderTimeoutQueue, DirectExchange orderDlxExchange) {
        return BindingBuilder.bind(orderTimeoutQueue)
                .to(orderDlxExchange)
                .with(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
    }

    /**
     * 7. JSON 消息转换器
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
