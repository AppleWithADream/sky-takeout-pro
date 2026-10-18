package com.sky.test;

import com.sky.mq.MqConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.HashSet;

/**
 * 订单超时关单（MQ 延迟消息）的测试工具类。
 *
 * <p>用来验证「订单 15 分钟未支付自动取消」这条链路，具体做三件事：
 * <ol>
 *   <li>造一条待付款订单：库里原本没有 status=1 的订单，所以临时造一条，测完删掉，不污染真实数据；</li>
 *   <li>发消息：可以直接进超时队列（跳过等待），也可以走延迟队列（能看到 TTL 过期后自动转发）；</li>
 *   <li>查看和清理：查订单状态有没有变成 6（已取消），测完删掉测试订单。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class OrderTimeoutTestDataSupportTest {

    /** 测试订单号前缀，删除时只删这个前缀的，不动真实订单 */
    private static final String TEST_ORDER_NUMBER_PREFIX = "TESTMQ";

    /** 发消息前把这个改成要测的订单 id */
    private static final Long TEST_ORDER_ID = 18L;

    @Resource
    private DataSource dataSource;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 造一条待付款订单（status=1），控制台会打印订单 id */
    @Test
    public void createPendingOrder() {
        String number = TEST_ORDER_NUMBER_PREFIX + System.currentTimeMillis();
        String sql = "INSERT INTO orders (number, status, user_id, address_book_id, order_time, "
                + "pay_method, pay_status, amount, remark, phone, address, user_name, consignee, "
                + "delivery_status, tableware_status) VALUES (?, 1, ?, ?, NOW(), 1, 0, ?, ?, ?, ?, ?, ?, 1, 1)";
        Long newId = null;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, number);
            ps.setLong(2, 9999L);
            ps.setLong(3, 1L);
            ps.setBigDecimal(4, new java.math.BigDecimal("9.99"));
            ps.setString(5, "测试数据");
            ps.setString(6, "13800000000");
            ps.setString(7, "测试地址");
            ps.setString(8, "测试用户");
            ps.setString(9, "测试收货人");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    newId = rs.getLong(1);
                }
            }
        } catch (Exception e) {
            System.err.println("造订单失败: " + e.getMessage());
            return;
        }
        System.out.println("测试订单已创建，id = " + newId + "，订单号 = " + number);
    }

    /** 查看订单表：状态分布、测试订单、待付款订单 */
    @Test
    public void inspectOrders() {
        printQuery("状态分布（1待付款 2待接单 3已接单 4派送中 5已完成 6已取消）",
                "SELECT status, COUNT(*) AS 条数 FROM orders GROUP BY status ORDER BY status");
        printQuery("测试订单（number 以 " + TEST_ORDER_NUMBER_PREFIX + " 开头）",
                "SELECT id, number, status, cancel_reason FROM orders WHERE number LIKE ? ORDER BY id DESC",
                TEST_ORDER_NUMBER_PREFIX + "%");
        printQuery("待付款订单",
                "SELECT id, number, order_time FROM orders WHERE status = 1 ORDER BY order_time");
    }

    /** 删掉所有测试订单（只删 TESTMQ 前缀的） */
    @Test
    public void deleteTestOrder() {
        int deleted = executeUpdate("DELETE FROM orders WHERE number LIKE ?", TEST_ORDER_NUMBER_PREFIX + "%");
        System.out.println("已删除测试订单 " + deleted + " 条，真实订单没动");
    }

    /** 清场（和 deleteTestOrder 一样，方便记） */
    @Test
    public void resetAll() {
        deleteTestOrder();
    }

    /** 发延迟消息：走完整链路，delay.queue 等 TTL 后转到 timeout.queue */
    @Test
    public void sendDelayMessage() {
        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_DELAY_EXCHANGE,
                MqConstants.ORDER_DELAY_ROUTING_KEY,
                TEST_ORDER_ID);
        System.out.println("已发延迟消息，orderId = " + TEST_ORDER_ID);
    }

    /** 发消息直接进超时队列，跳过 TTL 等待，立刻被消费 */
    @Test
    public void sendTimeoutMessage() {
        rabbitTemplate.convertAndSend("", MqConstants.ORDER_TIMEOUT_QUEUE, TEST_ORDER_ID);
        System.out.println("已发直达超时队列的消息，orderId = " + TEST_ORDER_ID);
    }

    /** 往最终失败队列发一条消息（该队列无消费者，验证完要 Purge） */
    @Test
    public void sendFailedMessage() {
        rabbitTemplate.convertAndSend(
                MqConstants.ORDER_DLX_EXCHANGE,
                MqConstants.ORDER_FAILED_ROUTING_KEY,
                TEST_ORDER_ID);
        System.out.println("已往最终失败队列发消息，orderId = " + TEST_ORDER_ID);
    }

    /** 发一条格式错误的消息（消息体不是数字），验证消息转换失败会怎样 */
    @Test
    public void sendBrokenMessage() {
        rabbitTemplate.convertAndSend("", MqConstants.ORDER_TIMEOUT_QUEUE, "不是合法的订单id");
        System.out.println("已发格式错误的消息，预期日志报转换失败，可能需要手动清 Unacked");
    }

    private int executeUpdate(String sql, Object... args) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("SQL 失败: " + e.getMessage());
            return -1;
        }
    }

    private void printQuery(String title, String sql, Object... args) {
        System.out.println("\n" + title);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                int rows = 0;
                while (rs.next()) {
                    StringBuilder line = new StringBuilder("  ");
                    for (int i = 1; i <= cols; i++) {
                        Object v = rs.getObject(i);
                        line.append(String.format("%-24s", v == null ? "NULL" : v.toString()));
                    }
                    System.out.println(line);
                    rows++;
                }
                if (rows == 0) {
                    System.out.println("  （没有数据）");
                }
            }
        } catch (Exception e) {
            System.err.println("查询失败: " + e.getMessage());
        }
    }
}
