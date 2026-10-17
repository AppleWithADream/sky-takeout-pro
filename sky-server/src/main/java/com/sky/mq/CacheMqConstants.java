package com.sky.mq;

/**
 * 菜品缓存删除 —— MQ 常量
 * 独立于订单超时那套（sky.order.*），用 sky.cache.* 前缀隔离，
 * 两套共用同一个 RabbitMQ，但命名空间分开，互不干扰。
 */
public class CacheMqConstants {

    /** 缓存交换机（Direct，持久化） */
    public static final String CACHE_EXCHANGE = "sky.cache.exchange";

    /** 菜品缓存删除队列：CacheDeleteListener 监听它 */
    public static final String CACHE_DISH_QUEUE = "sky.cache.dish.queue";

    /** 菜品缓存删除路由键 */
    public static final String CACHE_DISH_ROUTING_KEY = "sky.cache.dish";

    /** 死信队列：重试耗尽后进入，人工兜底 */
    public static final String CACHE_DLX_QUEUE = "sky.cache.dlx.queue";

    /** 死信路由键 */
    public static final String CACHE_DLX_ROUTING_KEY = "sky.cache.dlx";
}