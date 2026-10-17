package com.sky.controller.admin;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.RedisCallback;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import com.sky.mq.CacheMqConstants;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;


import java.util.List;
import java.util.Set;

/**
* 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品管理")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;
    @Autowired
    // 改成
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 缓存删除开关：true=MQ 异步删（默认）；false=回退同步 SCAN 删（旧逻辑保留） */
    @Value("${sky.cache.mq-enabled:true}")
    private boolean cacheMqEnabled;
    /**
    * 新增菜品
    * @param dishDTO
    * @return
     */
    @PostMapping
    @ApiOperation("新增菜品")
    public Result save(@RequestBody DishDTO dishDTO) {
        log.info("新增菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);

        //清理缓存数据
        /*String key = "dish_" + dishDTO.getCategoryId();
        redisTemplate.delete(key);*/

        //清理缓存数据
        cleanCache("dish_" + dishDTO.getCategoryId());
        return Result.success();
    }

    /**
    * 菜品分页查询
    * @param dishPageQueryDTO
    * @return
     */
    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> page(DishPageQueryDTO dishPageQueryDTO){
        log.info("菜品分页查询：{}", dishPageQueryDTO);
        PageResult pageResult = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(pageResult);
    }

    /**
    * 批量删除菜品
    * @param ids
    * @return
     */
    @DeleteMapping
    @ApiOperation("批量删除菜品")
    public Result delete(@RequestParam List<Long> ids){
        log.info("批量删除菜品：{}", ids);
        dishService.deleteBatch(ids);

        //将所有的菜品缓存数据清理掉，所有以dish_开头的缓存数据
        // keys实现：简单粗暴，全库扫描，阻塞Redis，**仅开发环境测试使用，禁止上线**
        /*Set keys = redisTemplate.keys("dish_*");
        redisTemplate.delete(keys);*/

        //SCAN 游标遍历删除匹配的 key，替代 keys（避免阻塞 Redis 单线程）
        //deleteKeysByPattern("dish_*");

        // 改走 MQ 异步删（开关可切回同步 SCAN）
        cleanCache("dish_*");

        return Result.success();
    }

    /**
    * 根据id查询菜品
    * @param id
    * @return
     */
    @GetMapping("/{id}")
    @ApiOperation("根据id查询菜品")
    public Result<DishVO> getById(@PathVariable Long id){
        log.info("根据id查询菜品：{}", id);
        DishVO dishVO = dishService.getByIdWithFlavor(id);
        return Result.success(dishVO);
    }

    /**
    * 修改菜品
    * @param dishDTO
    * @return
     */
    @PutMapping
    @ApiOperation("修改菜品")
    public Result update(@RequestBody DishDTO dishDTO){
        log.info("修改菜品：{}", dishDTO);
        dishService.updateWithFlavor(dishDTO);


        //将所有的菜品缓存数据清理掉，所有以dish_开头的缓存数据
        // keys实现：简单粗暴，全库扫描，阻塞Redis，**仅开发环境测试使用，禁止上线**
        /*Set keys = redisTemplate.keys("dish_*");
        redisTemplate.delete(keys);*/

        //SCAN 游标遍历删除匹配的 key，替代 keys（避免阻塞 Redis 单线程）
        //deleteKeysByPattern("dish_*");

        // 改走 MQ 异步删（开关可切回同步 SCAN）
        cleanCache("dish_*");

        return Result.success();
    }

    /**
    * 起售停售菜品状态
    * @param status
    * @param id
    * @return
     */
    @PostMapping("/status/{status}")
    @ApiOperation("修改菜品状态")
    public Result startOrStop(@PathVariable Integer status, Long id){
        log.info("修改菜品状态：{}，{}", status, id);
        dishService.startOrStop(status, id);

        //将所有的菜品缓存数据清理掉，所有以dish_开头的缓存数据
        // keys实现：简单粗暴，全库扫描，阻塞Redis，**仅开发环境测试使用，禁止上线**
        /*Set keys = redisTemplate.keys("dish_*");
        redisTemplate.delete(keys);*/

        //SCAN 游标遍历删除匹配的 key，替代 keys（避免阻塞 Redis 单线程）
        //deleteKeysByPattern("dish_*");

        // 改走 MQ 异步删（开关可切回同步 SCAN）
        cleanCache("dish_*");

        return Result.success();
    }
    /**
    * 根据分类id查询菜品列表
    * @param categoryId
    * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品列表")
    public Result<List<Dish>> list(Long categoryId){
        log.info("根据分类id查询菜品列表：{}", categoryId);
        List<Dish> dishList = dishService.list(categoryId);
        return Result.success(dishList);
    }

    /**
     * 清理菜品缓存（开关控制）
     * MQ 开（默认）→ 发消息给 CacheDeleteListener 异步删
     *                 · 接口不再依赖 Redis 的实时可用性
     *                 · 删失败自动重试 5 次，耗尽进死信队列
     * MQ 关        → 走旧的同步 SCAN 删（deleteKeysByPattern 原样保留）
     * @param pattern 要删除的 key 或匹配模式，如 "dish_5" / "dish_*"
     */
    private void cleanCache(String pattern) {
        if (cacheMqEnabled) {
            rabbitTemplate.convertAndSend(
                    CacheMqConstants.CACHE_EXCHANGE,
                    CacheMqConstants.CACHE_DISH_ROUTING_KEY,
                    pattern);
            log.info("已发缓存删除消息：pattern={}", pattern);
        } else {
            deleteKeysByPattern(pattern);
        }
    }


    /**
     * SCAN 游标遍历删除匹配的 key，替代 KEYS。
     * KEYS：一条命令全库扫描，长时间独占 Redis 单线程 → 其他请求全部排队
     * SCAN：拆成多次小批量执行，每次阻塞很短 → 其他请求几乎无感
     * 注意：SCAN 的【总耗时】不比 KEYS 少（甚至更长），它买到的是"可响应性"，不是"更快"。
     *      本方法最后仍是一次性 delete，删除阶段仍会阻塞，留待后续优化。
     * @param pattern key 匹配模式，如 "dish_*"
     */
    private void deleteKeysByPattern(String pattern) {
        Set<String> keys = redisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> result = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(5000).build())) {
                while (cursor.hasNext()) {
                    result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return result;
        });
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

}
