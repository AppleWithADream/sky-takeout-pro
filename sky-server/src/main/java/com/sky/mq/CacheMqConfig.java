package com.sky.mq;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 菜品缓存删除 —— MQ 配置
 */
@Configuration
public class CacheMqConfig {

    /** 1. 缓存交换机（Direct，持久化） */
    @Bean
    public DirectExchange cacheExchange() {
        return new DirectExchange(CacheMqConstants.CACHE_EXCHANGE, true, false);
    }

    /**
     * 2. 菜品缓存删除队列
     *    死信：重试耗尽 reject 后进 sky.cache.dlx.queue 人工兜底
     */
    @Bean
    public Queue cacheDishQueue() {
        return QueueBuilder.durable(CacheMqConstants.CACHE_DISH_QUEUE)
                .deadLetterExchange(CacheMqConstants.CACHE_EXCHANGE)
                .deadLetterRoutingKey(CacheMqConstants.CACHE_DLX_ROUTING_KEY)
                .build();
    }

    /** 3. 绑定：缓存交换机 → 菜品缓存删除队列 */
    @Bean
    public Binding cacheDishBinding(Queue cacheDishQueue, DirectExchange cacheExchange) {
        return BindingBuilder.bind(cacheDishQueue)
                .to(cacheExchange)
                .with(CacheMqConstants.CACHE_DISH_ROUTING_KEY);
    }

    /** 4. 死信队列：删缓存重试耗尽 → 人工处理（不配消费者） */
    @Bean
    public Queue cacheDlxQueue() {
        return QueueBuilder.durable(CacheMqConstants.CACHE_DLX_QUEUE).build();
    }

    /** 5. 绑定：缓存交换机 → 死信队列 */
    @Bean
    public Binding cacheDlxBinding(Queue cacheDlxQueue, DirectExchange cacheExchange) {
        return BindingBuilder.bind(cacheDlxQueue)
                .to(cacheExchange)
                .with(CacheMqConstants.CACHE_DLX_ROUTING_KEY);
    }

    /**
     * 6. 缓存删除专用容器工厂（Bean 名 = cacheContainerFactory，只给 CacheDeleteListener 用）
     *
     * · AUTO ACK：方法正常返回自动确认，抛异常自动触发重试
     * · 重试 5 次：1 秒起步、指数退避（×2）、上限 10 秒
     * · 耗尽 → RejectAndDontRequeueRecoverer → reject(false) → 进死信队列
     * ⚠️ 订单超时那套 OrderTimeoutListener 没指定本工厂 → 继续用全局默认工厂（yaml manual ACK），互不干扰
     */
    @Bean("cacheContainerFactory")
    public SimpleRabbitListenerContainerFactory cacheContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // ★ 必须设转换器，否则用默认的 SimpleMessageConverter（Java 序列化），和 JSON 消息对不上
        factory.setMessageConverter(messageConverter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setPrefetchCount(1);

        // 重试 5 次：1s → 2s → 4s → 8s → 10s（上限），耗尽后 reject(false) 进死信
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(5)
                        .backOffOptions(1000, 2.0, 10000)
                        .recoverer(new RejectAndDontRequeueRecoverer())
                        .build());

        return factory;
    }
}