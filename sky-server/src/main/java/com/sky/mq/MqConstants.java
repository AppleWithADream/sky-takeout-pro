package com.sky.mq;

/**
 * 订单超时关单 —— MQ 常量
 */
public class MqConstants {

    /** 延迟交换机：下单成功后把消息发到这里 */
    public static final String ORDER_DELAY_EXCHANGE = "sky.order.delay.exchange";
    /** 延迟队列：TTL 15 分钟，【故意不配消费者】，消息在这里干等到期 */
    public static final String ORDER_DELAY_QUEUE = "sky.order.delay.queue";
    /** 延迟路由键 */
    public static final String ORDER_DELAY_ROUTING_KEY = "sky.order.delay";

    /** 死信交换机：延迟队列里到期的消息被重新发布到这里 */
    public static final String ORDER_DLX_EXCHANGE = "sky.order.dlx.exchange";
    /** 超时处理队列：OrderTimeoutListener 监听它 */
    public static final String ORDER_TIMEOUT_QUEUE = "sky.order.timeout.queue";
    /** 超时路由键 */
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "sky.order.timeout";


    // ==================== 失败兜底 ====================

    /** 最终失败队列：重试次数用尽后进入，人工处理 */
    public static final String ORDER_FAILED_QUEUE = "sky.order.failed.queue";
    /** 最终失败路由键 */
    public static final String ORDER_FAILED_ROUTING_KEY = "sky.order.failed";
    // ==================== 延迟重试 ====================

    /** 重试队列：TTL 30 秒，【故意不配消费者】，到期后自动回到超时处理队列 */
    public static final String ORDER_RETRY_QUEUE = "sky.order.retry.queue";
    /** 重试路由键 */
    public static final String ORDER_RETRY_ROUTING_KEY = "sky.order.retry";
    /** 消息头里记录"已重试次数"的键名 */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";
    /** 最大重试次数（超过就进最终失败队列） */
    public static final int MAX_RETRY_COUNT = 3;
}