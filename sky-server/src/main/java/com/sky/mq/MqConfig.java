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
                .ttl(15 * 60 * 1000)                               // 15 分钟（毫秒）
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

    /**
     * 5. 超时处理队列
     *    · 业务异常被 basicReject(requeue=false) 后，消息【直接消失】
     *    · 消息读不懂（框架级 Fatal）时，消息【卡在 Unacked】，把消费者占死
     */

    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE)
                .deadLetterExchange(MqConstants.ORDER_DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.ORDER_FAILED_ROUTING_KEY)
                .build();
    }

    /** 6. 绑定：死信交换机 → 超时处理队列 */
    @Bean
    public Binding orderTimeoutBinding(Queue orderTimeoutQueue, DirectExchange orderDlxExchange) {
        return BindingBuilder.bind(orderTimeoutQueue)
                .to(orderDlxExchange)
                .with(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
    }


    /**
     * 7. 重试队列：TTL 30 秒，到期后回到超时处理队列重试
     * ⚠️【故意不配消费者】—— 它的唯一作用就是"让消息等 30 秒"。
     *    配了消费者就变成"立刻处理"，失去延迟重试的意义。
     */
    @Bean
    public Queue orderRetryQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_RETRY_QUEUE)
                .ttl(30 * 1000)                                              // 等 30 秒
                .deadLetterExchange(MqConstants.ORDER_DLX_EXCHANGE)          // 到期后回死信交换机
                .deadLetterRoutingKey(MqConstants.ORDER_TIMEOUT_ROUTING_KEY) // 路由到【超时队列】重试
                .build();
    }

    /** 8. 绑定：死信交换机 -> 重试队列（路由键 sky.order.retry） */
    @Bean
    public Binding orderRetryBinding(Queue orderRetryQueue, DirectExchange orderDlxExchange) {
        return BindingBuilder.bind(orderRetryQueue)
                .to(orderDlxExchange)
                .with(MqConstants.ORDER_RETRY_ROUTING_KEY);
    }

    /**
     * 9. 最终失败队列：重试次数用尽后进这里
     * ⚠️ 也【不配消费者】—— 它代表"系统自动处理不了的异常"，本来就该人工介入。
     *    生产环境建议加"队列堆积告警"（比如 Ready 超过 100 就发通知）。
     */
    @Bean
    public Queue orderFailedQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_FAILED_QUEUE).build();
    }

    /** 10. 绑定：死信交换机 -> 最终失败队列（路由键 sky.order.failed） */
    @Bean
    public Binding orderFailedBinding(Queue orderFailedQueue, DirectExchange orderDlxExchange) {
        return BindingBuilder.bind(orderFailedQueue)
                .to(orderDlxExchange)
                .with(MqConstants.ORDER_FAILED_ROUTING_KEY);
    }

    /**
     * 11. JSON 消息转换器
     */
    @Bean
    public MessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
