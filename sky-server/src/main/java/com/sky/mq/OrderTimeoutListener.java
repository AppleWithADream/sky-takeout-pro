package com.sky.mq;

import com.rabbitmq.client.Channel;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 订单超时关单消费者
 * <p>处理失败时：不 reject，而是主动把消息重新发布到 retry.queue（30 秒后自动回到本队列重试），
 * 重试次数记在消息头 x-retry-count 里，超过 3 次进 failed.queue。
 */
@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE, ackMode = "MANUAL")
    public void handleTimeoutOrder(
            Long orderId,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
            @Header(name = MqConstants.HEADER_RETRY_COUNT, required = false) Integer retryCount) throws IOException {

        // 当前是第几次处理（第一次进来时消息头里没有这个字段，视为 0）
        int currentRetry = (retryCount == null) ? 0 : retryCount;

        try {
            // ---------- 业务：查订单 → 待付款则取消 ----------
            Orders orders = orderMapper.getById(orderId);
            if (orders == null) {
                log.warn("订单不存在，忽略：orderId={}", orderId);
            } else if (Orders.PENDING_PAYMENT.equals(orders.getStatus())) {
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时，自动取消");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
                log.info("订单超时已取消：orderId={}", orderId);
            } else {
                log.info("订单状态已变更，无需取消：orderId={}, status={}", orderId, orders.getStatus());
            }

            // 处理成功（包括"订单状态已变更，本就不该取消"）→ 确认
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("超时订单处理失败（第 {} 次）：orderId={}", currentRetry + 1, orderId, e);

            if (currentRetry < MqConstants.MAX_RETRY_COUNT) {
                // 还有重试机会 → 丢进重试队列，30 秒后自动回来
                final int nextRetry = currentRetry + 1;
                rabbitTemplate.convertAndSend(
                        MqConstants.ORDER_DLX_EXCHANGE,
                        MqConstants.ORDER_RETRY_ROUTING_KEY,
                        orderId,
                        message -> {
                            message.getMessageProperties()
                                    .setHeader(MqConstants.HEADER_RETRY_COUNT, nextRetry);
                            return message;
                        });
                log.warn("已投递到重试队列（30 秒后第 {} 次重试）：orderId={}", nextRetry + 1, orderId);
            } else {
                // 次数用尽 → 转最终失败队列，人工处理
                rabbitTemplate.convertAndSend(
                        MqConstants.ORDER_DLX_EXCHANGE,
                        MqConstants.ORDER_FAILED_ROUTING_KEY,
                        orderId);
                log.error("重试 {} 次仍失败，转入最终失败队列，需人工处理：orderId={}",
                        MqConstants.MAX_RETRY_COUNT, orderId);
            }

            // 无论走哪条路，原消息都已处理完毕 → 确认掉
            //  用 basicAck 而不是 basicReject：内容已经复制出去了，再 reject 会被队列的 DLX 又送一遍，造成重复
            channel.basicAck(deliveryTag, false);
        }
    }
}