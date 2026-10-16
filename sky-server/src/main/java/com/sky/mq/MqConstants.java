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
}