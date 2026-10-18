package com.sky.test;

import com.sky.mq.CacheMqConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.util.Set;

/**
 * 菜品缓存删除（MQ 异步）的测试工具类。
 * 用来验证「改完数据库后发 MQ 消息、消费者异步删缓存」这条链路。具体做三件事：
 * <ol>
 *   <li>造缓存：往 Redis 写 dish_11 / dish_13，模拟用户查过菜品列表；</li>
 *   <li>发消息：模拟改菜品（删全部 dish_*）和新增菜品（只删 dish_11）两种场景；</li>
 *   <li>查看和清理：看缓存有没有被删掉，测完清干净。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CacheDeleteTestDataSupportTest {

    /** 造缓存用的分类 id，对应 dish_11 */
    private static final Long CATEGORY_ID = 11L;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /** 造两个缓存 key：dish_11 和 dish_13 */
    @Test
    public void createCache() {
        redisTemplate.opsForValue().set("dish_" + CATEGORY_ID, "缓存内容-" + CATEGORY_ID);
        redisTemplate.opsForValue().set("dish_13", "缓存内容-13");
        System.out.println("已造缓存：dish_" + CATEGORY_ID + " / dish_13");
    }

    /** 查看当前所有 dish_ 开头的缓存 key */
    @Test
    public void inspectCache() {
        Set<String> keys = redisTemplate.keys("dish_*");
        System.out.println("当前 dish_ key：" + (keys == null || keys.isEmpty() ? "（无）" : keys));
    }

    /** 清掉所有 dish_ 开头的缓存（不碰 setmealCache 等其他 key） */
    @Test
    public void clearCache() {
        Set<String> keys = redisTemplate.keys("dish_*");
        if (keys == null || keys.isEmpty()) {
            System.out.println("没有 dish_ 缓存，不用清");
            return;
        }
        Long deleted = redisTemplate.delete(keys);
        System.out.println("已清理 dish_ 缓存 " + deleted + " 个");
    }

    /** 发消息删除全部菜品缓存（pattern = dish_*），模拟改菜品/删菜品时的消息 */
    @Test
    public void sendDeleteAll() {
        rabbitTemplate.convertAndSend(
                CacheMqConstants.CACHE_EXCHANGE,
                CacheMqConstants.CACHE_DISH_ROUTING_KEY,
                "dish_*");
        System.out.println("已发消息：dish_*（删全部）");
    }

    /** 发消息只删单个分类的缓存（pattern = dish_11），模拟新增菜品时的消息 */
    @Test
    public void sendDeleteOne() {
        rabbitTemplate.convertAndSend(
                CacheMqConstants.CACHE_EXCHANGE,
                CacheMqConstants.CACHE_DISH_ROUTING_KEY,
                "dish_" + CATEGORY_ID);
        System.out.println("已发消息：dish_" + CATEGORY_ID + "（只删这一个）");
    }

    /**
     * 发一条消息，但必须先停掉 Redis，才能验证重试和死信。
     * 不停 Redis 的话，SCAN 匹配不到 key 也会正常返回，不会触发重试。
     */
    @Test
    public void sendFailMessage() {
        rabbitTemplate.convertAndSend(
                CacheMqConstants.CACHE_EXCHANGE,
                CacheMqConstants.CACHE_DISH_ROUTING_KEY,
                "dish_*");
        System.out.println("已发消息：dish_*（前提是 Redis 已停掉，否则不会重试）");
    }
}
