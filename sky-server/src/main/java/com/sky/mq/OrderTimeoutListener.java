package com.sky.mq;

import com.rabbitmq.client.Channel;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 订单超时关单消费者
 * 收到 TTL 过期的消息 → 查订单 → 只有"待付款(1)"才取消。
 * 这个 status 判断同时保证【幂等】：万一消息重复投递，第二次进来状态已经是 6，
 */
@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private OrderMapper orderMapper;

    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE, ackMode = "MANUAL")
    public void handleTimeoutOrder(Long orderId, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            Orders orders = orderMapper.getById(orderId);
            if (orders == null) {
                log.warn("订单不存在，忽略：orderId={}", orderId);
            } else if (Orders.PENDING_PAYMENT.equals(orders.getStatus())) {
                // 15 分钟还是待付款 → 取消
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时，自动取消");
                orders.setCancelTime(LocalDateTime.now());
                orderMapper.update(orders);
                log.info("订单超时已取消：orderId={}", orderId);
            } else {
                // 已支付或状态已变 → 不能取消
                log.info("订单状态已变更，无需取消：orderId={}, status={}", orderId, orders.getStatus());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("超时订单处理失败：orderId={}", orderId, e);
            channel.basicReject(deliveryTag, false);   // 不重回队列，避免死循环
        }
    }
}