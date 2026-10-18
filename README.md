# SkyOrder · 外卖平台后端

面向餐饮商家的一体化外卖平台后端，包含**管理端**（员工、菜品、套餐、订单、数据报表）和**用户端**（浏览、购物车、下单、支付、收货）两套完整业务。

围绕缓存、消息、定时任务三个方向做了四项改造，每项都有实测数据支撑。

---

## 技术栈

| 分类 | 技术 |
|---|---|
| 框架 | Spring Boot 2.7.3、Spring MVC、Spring AOP |
| 持久层 | MyBatis、PageHelper、Druid 连接池 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis（Spring Data Redis） |
| 消息队列 | RabbitMQ（Spring AMQP） |
| 认证 | JWT（jjwt） |
| 接口文档 | Knife4j（Swagger） |
| 其他 | WebSocket（来单提醒）、阿里云 OSS、Apache POI（报表导出） |

**代码规模**：144 个 Java 文件，约 6600 行

---

## 项目结构

```
sky-take-out
├── sky-common          公共模块：常量、上下文、异常、工具类、统一返回
├── sky-pojo            实体模块：DTO / VO / Entity
└── sky-server          服务模块
    ├── annotation      自定义注解（公共字段自动填充）
    ├── aspect          AOP 切面
    ├── config          配置类（Redis / OSS / WebMvc / WebSocket）
    ├── controller      接口层（admin 管理端 / user 用户端）
    ├── handler         全局异常处理
    ├── interceptor     JWT 拦截器
    ├── mapper          MyBatis Mapper
    ├── mq              消息队列（本项目的优化重点）
    ├── service         业务层
    ├── task            定时任务
    └── websocket       来单提醒
```

---

## 四项优化改造

### 一、菜品缓存清理：`KEYS` → `SCAN` 渐进遍历

**问题**

菜品增删改后需要清理 `dish_*` 缓存。原实现用 `KEYS dish_*` —— 一条命令全库扫描，**独占 Redis 单线程**，key 多时会把其他请求全部阻塞。

**改造**

改用 `SCAN` 游标分批遍历（`count=5000`），把一次长阻塞拆成多次短阻塞，最后批量 `DEL`。

```java
try (Cursor<byte[]> cursor = connection.scan(
        ScanOptions.scanOptions().match(pattern).count(5000).build())) {
    while (cursor.hasNext()) {
        resultKeySet.add(new String(cursor.next(), StandardCharsets.UTF_8));
    }
}
```

**压测数据**（JMeter，20 万 key，20000 样本）

| 指标 | 基线（无清理） | `KEYS` 方案 | `SCAN` 方案 |
|---|---|---|---|
| p99 响应时间 | 8ms | **16ms** | **5ms** |
| 最大响应时间 | 171ms | **384ms** | **191ms** |
| 吞吐量 | 8058/s | **5660/s** | **9891/s** |
| 清理操作耗时 | — | 955ms | 582ms |

**结论**：`KEYS` 一次扫描独占 Redis 约 955ms，导致 p99 翻倍、吞吐掉 30%；改用 `SCAN` 后 p99 回落到 5ms（低于基线），吞吐恢复到 9891/s。

---

### 二、订单超时关单：`Spring Task` 轮询 → `MQ` 延迟消息

**问题**

原实现用 `@Scheduled(cron = "0 * * * * ?")` 每分钟扫一次数据库，把超时未支付订单置为取消。**轮询有两个硬伤**：
- 订单实际取消时间最多偏差 1 分钟
- 订单量增长后，全表扫描压力线性上升

**改造**

用 **TTL + 死信交换机** 实现延迟消息：

```
下单 ──→ sky.order.delay.queue（TTL=15分钟，无消费者）
              │ 到期变死信
              ▼
        sky.order.dlx.exchange
              │ 路由键 sky.order.timeout
              ▼
        sky.order.timeout.queue ──→ OrderTimeoutListener（消费、判断、关单）
```

- 消息体只有 `orderId`（`Long`）
- 消费端**三重判断**：订单不存在 → 忽略；状态已变更 → 幂等跳过；仍是待付款 → 取消
- TTL 到期由 Broker 精确触发，**不再依赖定时扫描**

**验证结果**

| 场景 | 结果 |
|---|---|
| 正常关单 | `status` 1 → 6，取消原因写入「订单超时，自动取消」 |
| 幂等（重复消息） | 日志「订单状态已变更，无需取消」，不产生重复 UPDATE |
| 订单不存在 | 日志「订单不存在，忽略」，安全退出 |

---

### 三、关单失败处理：延迟重试 + 死信兜底

**问题**

超时关单跑通后 review 代码发现：消费者处理失败时 `basicReject(requeue=false)`，但 `sky.order.timeout.queue` 上**没有配置死信参数** —— 消息不是「进死信队列没人管」，而是**直接消失**。订单永远不会被取消，库存被僵尸订单长期占用。

更严重的是：**如果消息本身读不懂**（反序列化失败），框架在进入监听方法之前就失败了，消息会**卡在 Unacked**；配合 `prefetch=1`，整个消费者被占死。

**改造：两级防线**

**第一级 · 队列层**（兜住代码捕获不到的失败）

给 `sky.order.timeout.queue` 配置死信参数，指向 `sky.order.failed.queue`。

**第二级 · 逻辑层**（延迟重试）

```
处理失败
   ├─ 已重试 < 3 次 → 发到 sky.order.retry.queue（TTL=30秒）
   │                      └─ 到期自动回到 timeout.queue 重试
   └─ 已重试 ≥ 3 次 → 发到 sky.order.failed.queue（人工处理）
```

- **重试次数记在消息头** `x-retry-count`，随消息走，跨重启不丢
- 重试是**异步**的（消息重新入队），不占用消费者线程
- 失败时用 `basicAck` 而非 `basicReject` —— 消息内容已复制到新队列，再 reject 会被队列的 DLX 又转发一遍，产生重复

**完整拓扑**

| 队列 | 消费者 | 关键参数 |
|---|---|---|
| `sky.order.delay.queue` | 无 | `x-message-ttl=900000`、死信 → timeout |
| `sky.order.timeout.queue` | 1 | 死信 → failed |
| `sky.order.retry.queue` | 无 | `x-message-ttl=30000`、死信 → timeout |
| `sky.order.failed.queue` | 无 | 终点，人工介入 |

**验证结果**（人为把订单表改名制造查库失败）

| 时刻 | 日志 | 队列状态 |
|---|---|---|
| t=10s | 第 1 次失败，投递重试队列 | `retry.queue` Ready=1 |
| t=40s | 第 2 次失败 | 1→0→1 |
| t=70s | 第 3 次失败 | —— |
| t=100s | 第 4 次失败，转入最终失败队列 | `failed.queue` Ready=1 |
| 全程 | —— | `timeout.queue` 的 **Unacked 始终为 0**（消息未卡住）|

---

### 四、缓存删除：同步执行 → `MQ` 异步

**问题**

菜品 / 套餐的增删改，原实现是「先改数据库、再同步删 Redis 缓存」。这里有个隐患：**MySQL 和 Redis 不在同一个事务里**。

- 数据库提交成功后，删缓存时 Redis 抖动 → 接口返回 500
- 但**数据库其实已经改了**，用户看到「修改失败」实际成功了
- 缓存**没删掉**，后续查询拿到的还是旧数据 → 两边数据分叉
- 删缓存失败**没有重试**，Redis 恢复后缓存依然是脏的

**改造**

改完数据库后发一条 MQ 消息，消费者异步删缓存：

```
DishController
   │ cleanCache(pattern) —— 事务已提交（事务边界在 Service 层）
   ▼
sky.cache.exchange（Direct）
   │ 路由键 sky.cache.dish
   ▼
sky.cache.dish.queue ──→ CacheDeleteListener（SCAN 遍历删除）
   │                          ├─ 成功 → AUTO ACK
   │                          ├─ 失败 → 自动重试 5 次（1s→2s→4s→8s）
   │                          └─ 耗尽 → 死信 → sky.cache.dlx.queue
   │
   └─ 死信路由键 sky.cache.dlx ──→ sky.cache.dlx.queue（人工兜底）
```

**关键设计**

| 设计点 | 说明 |
|---|---|
| **命名空间隔离** | `sky.cache.*` 与订单超时的 `sky.order.*` 完全分开，用**独立的容器工厂**，ACK 模式和重试策略互不影响 |
| **消息体统一为 String** | `save()` 删单个 key（`dish_11`），其他方法删全部（`dish_*`）。`SCAN` 的 `match` 参数本身是 glob 模式，不带通配符时只匹配自己，**一套逻辑覆盖两种场景** |
| **AUTO ACK + 框架重试** | 删缓存是幂等操作，用 `RetryInterceptorBuilder.stateless()` 重试 5 次即可，不需要像关单那样精确控制次数 |
| **配置开关** | `sky.cache.mq-enabled`，关闭即切回原来的同步 SCAN 删除，方便对比测试 |
| **原方法保留** | `deleteKeysByPattern()` 一行未改，作为回退路径 |

**验证结果**

| 场景 | 结果 |
|---|---|
| 正常删除 | 改菜品 → 日志「已发缓存删除消息」→ 6ms 后「缓存删除完成，删除 5 个 key」→ Redis 清空 |
| 精确删除 | 消息 `dish_11` 只删 `dish_11`，`dish_13` 不受影响 |
| 失败重试 | 停掉 Redis → 重试 5 次（间隔 1s/2s/4s/8s）→ `RejectAndDontRequeueRecoverer: Retries exhausted` → 进死信队列 |
| 消费者未卡死 | 重试全程原队列 `Unacked` 最终归 0 |
| 真实前端操作 | 管理端改菜品 → 数据库更新 → Controller 发消息 → 消费者删缓存，全链路贯通 |

**代价说明**

框架自带的 `stateless` 重试是**同步阻塞**的（在同一条消费者线程内 sleep），5 次重试约占用 15 秒。对「改菜品」这种低频操作可以接受；如果是高并发场景，应改为延迟队列重试（但那与订单超时那套机制重复）。

---

## 快速开始

### 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 8+ |
| Maven | 3.6+ |
| MySQL | 8.0 |
| Redis | 5.0+ |
| RabbitMQ | 3.8+ |

### 启动步骤

```bash
# 1. 建库并导入数据
mysql -uroot -p -e "CREATE DATABASE sky_take_out DEFAULT CHARSET utf8mb4;"
mysql -uroot -p sky_take_out < sky_take_out.sql

# 2. 修改配置
#    编辑 sky-server/src/main/resources/application-dev.yml
#    填写你自己的 MySQL 密码、Redis 地址、OSS / 微信配置

# 3. 启动 RabbitMQ 和 Redis

# 4. 启动应用
mvn clean package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar

# 或者直接在 IDEA 里运行 SkyApplication
```

### 访问地址

| 地址 | 说明 |
|---|---|
| `http://localhost:8080/doc.html` | Knife4j 接口文档 |
| `http://localhost:8080/admin/**` | 管理端接口（需 JWT） |
| `http://localhost:8080/user/**` | 用户端接口（需 JWT） |

**默认管理员账号**：`admin` / `123456`

---

## 核心功能清单

**管理端**：员工管理、分类管理、菜品管理、套餐管理、订单管理、营业状态、数据报表（营业额 / 用户 / 订单 / 销量 Top10）、工作台

**用户端**：微信登录、浏览菜品套餐、购物车、地址簿、下单、支付、历史订单

**技术亮点**：
- 公共字段自动填充（AOP + 自定义注解）
- JWT 双端鉴权（管理端 / 用户端拦截器分离）
- Redis 缓存菜品与套餐（旁路缓存策略）
- WebSocket 来单语音提醒
- Apache POI 导出运营数据报表

---

## 待优化项

| 项 | 现状 | 改进方向 |
|---|---|---|
| 缓存删除重试 | 同步阻塞重试（约 15 秒） | 改为延迟队列重试，不占消费者线程 |
| 消息转换失败 | 框架级异常，重试逻辑覆盖不到 | 消费方法改为接收 `Message`，自行解析 body |
| 死信队列监控 | 无告警 | 接入队列堆积监控 |
| 缓存一致性 | 删除策略（可能短暂脏读） | 可评估延迟双删 |
