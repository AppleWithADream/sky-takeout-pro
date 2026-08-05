package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 *  定时任务类·，定时处理订单状态
 */
@Component
@Slf4j
public class OrderTask {

    @Autowired
    private OrderMapper orderMapper;
    /**
     * 处理超时订单的方法
    */
    @Scheduled(cron = "0 * * * * ?")// 每分钟执行一次
    public void prcessTimeoutOrder() {
        log.info("订单超时取消任务启动");
        //select * from orders where status = ? and order_time < (now() - 15)
        List<Orders> list = orderMapper.getByStatusAndOrderTimele(Orders.PENDING_PAYMENT, LocalDateTime.now().plusMinutes(-15));

        if (list != null && list.size() > 0) {
            for (Orders orders : list) {
                orders.setStatus(Orders.CANCELLED);
                orders.setCancelReason("订单超时，自动取消");
                orders.setCancelTime(LocalDateTime.now());
                //修改订单状态
                orderMapper.update(orders);
            }
        }
    }

    /**
     * 处理派送订单的方法
     */
    @Scheduled(cron = "0 0 1 * * ?")// 每天凌晨1点执行一次
    public void processDeliveryOrder(){
        log.info("处理派送订单");
        List<Orders> list = orderMapper.getByStatusAndOrderTimele(Orders.DELIVERY_IN_PROGRESS, LocalDateTime.now().plusMinutes(-60));
        if (list != null && list.size() > 0) {
            for (Orders orders : list) {
                orders.setStatus(Orders.COMPLETED);
                //修改订单状态
                orderMapper.update(orders);
            }
        }
    }
}
