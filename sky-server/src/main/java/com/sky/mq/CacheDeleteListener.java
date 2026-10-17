package com.sky.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 菜品缓存删除消费者
 *
 * 收到 pattern（如 "dish_*" 或 "dish_5"）→ SCAN 游标遍历删除匹配 key。
 * 失败处理：
 *   · 由 cacheContainerFactory 的重试拦截器捕获 → 自动重试 5 次
 *   · 5 次耗尽 → RejectAndDontRequeueRecoverer → reject(false) → 进死信队列
 */
@Slf4j
@Component
public class CacheDeleteListener {

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheDeleteListener(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = CacheMqConstants.CACHE_DISH_QUEUE,
            containerFactory = "cacheContainerFactory")
    public void deleteCache(String pattern) {
        log.info("收到缓存删除消息：pattern={}", pattern);

        // 拿到Redis底层连接，执行SCAN扫描，收集所有匹配pattern的key
        Set<String> keys = redisTemplate.execute(new RedisCallback<Set<String>>() {
            @Override
            public Set<String> doInRedis(RedisConnection connection) throws DataAccessException {
                // 创建集合，用来存放扫描出来所有符合条件的key
                Set<String> resultKeySet = new HashSet<>();

                // 构建SCAN的参数：匹配规则，每次建议读取5000条
                ScanOptions scanOptions = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(5000)
                        .build();

                // try-with-resources：游标cursor用完自动关闭，防止资源泄漏
                try (Cursor<byte[]> cursor = connection.scan(scanOptions)) {
                    // 循环：游标还有下一条key就继续读
                    while (cursor.hasNext()) {
                        // 取出key的字节数组，Redis底层存的都是byte[]
                        byte[] keyBytes = cursor.next();
                        // 将字节数组转成UTF-8的字符串key
                        String keyStr = new String(keyBytes, StandardCharsets.UTF_8);
                        // 把key存入集合
                        resultKeySet.add(keyStr);
                    }
                }

                // 返回收集好的全部key
                return resultKeySet;
            }
        });

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("缓存删除完成：pattern={}, 删除 {} 个 key", pattern, keys.size());
        } else {
            log.info("缓存删除完成：pattern={}, 无匹配 key", pattern);
        }
    }
}