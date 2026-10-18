package com.sky.test;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis KEYS 与 SCAN 的性能对比测试。
 * 四个方法：
 *   {@code generateTestData()} —— 造 20 万个 dish_bench_* 测试 key，给 JMeter 压测用；
 *   {@code cleanTestData()} —— 把这些测试数据删掉；
 *   {@code keysVsScanLatency()} —— 测 KEYS 和 SCAN 单次耗时随库大小的变化；
 *   {@code keysBlocksOtherClients()} —— 测 KEYS 执行期间，另一个连接的 PING 被拖慢多少。
 *
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RedisKeysVsScanBenchmarkTest {

    /** Redis 地址，可用 -Dredis.uri 覆盖 */
    private static final String REDIS_URI = System.getProperty("redis.uri", "redis://localhost:6379");

    /** generateTestData 造多少个 key */
    private static final int TEST_DATA_COUNT = 200000;

    /** 测耗时时用的三档规模 */
    private static final int[] SCALES = {500, 5000, 20000};

    /** 测试 key 前缀，用 dish_ 开头保证匹配模式和生产一致 */
    private static final String BENCH_KEY_PREFIX = "dish_bench_";

    /** 常驻测试 key 前缀，故意不以 dish_ 开头，这样后台代码删不掉，可以反复触发 KEYS */
    private static final String OTHER_KEY_PREFIX = "bench:other:";
    private static final int OTHER_TEST_DATA_COUNT = 5000;

    /** 和生产代码一样的匹配模式 */
    private static final String MATCH_PATTERN = "dish_*";

    private static final int SCAN_COUNT = 1000;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 10;
    private static final long BLOCK_WINDOW_MS = 2000;
    private static final int SEED_BATCH_SIZE = 500;

    private static RedisClient client;
    /** 工作连接：跑 KEYS / SCAN */
    private static StatefulRedisConnection<String, String> workerConn;
    /** 探针连接：测"别人被卡住多久" */
    private static StatefulRedisConnection<String, String> probeConn;
    private static RedisCommands<String, String> worker;
    private static RedisCommands<String, String> probe;

    @BeforeAll
    static void beforeAll() {
        client = RedisClient.create(REDIS_URI);
        workerConn = client.connect();
        probeConn = client.connect();
        worker = workerConn.sync();
        probe = probeConn.sync();
        System.out.println("Redis 连接成功: " + worker.ping() + " -> " + REDIS_URI);
        cleanLeftovers();
    }

    @AfterAll
    static void afterAll() {
        // 刻意不清理：generateTestData 造的数据要留给 JMeter，需要清时跑 cleanTestData()
        if (workerConn != null) workerConn.close();
        if (probeConn != null) probeConn.close();
        if (client != null) client.shutdown();
    }

    /** 造 20 万个 dish_bench_* 测试 key，给 JMeter 压测用 */
    @Test
    @Order(0)
    @DisplayName("准备压测数据：写入 20 万个 dish_bench_* 测试 key")
    void generateTestData() {
        long t0 = System.currentTimeMillis();
        seed(TEST_DATA_COUNT);
        long cost = System.currentTimeMillis() - t0;
        printRedisState();
        System.out.println("耗时 " + cost + " ms，数据已就绪，可以去 JMeter 压测了");
        System.out.println("提醒：后台菜品接口一执行就会删掉这批数据，下次压测前要重新跑本方法");
    }

    /** 造 5000 个 bench:other:* 测试 key，后台代码删不掉，可以反复触发 KEYS */
    @Test
    @Order(0)
    @DisplayName("准备常驻压测数据：写入 5000 个不被删掉的 key")
    void generateOtherTestData() {
        long t0 = System.currentTimeMillis();
        seedWithPrefix(OTHER_KEY_PREFIX, OTHER_TEST_DATA_COUNT);
        long cost = System.currentTimeMillis() - t0;
        printRedisState();
        System.out.println("耗时 " + cost + " ms，这批数据不会被后台接口删掉，可以反复压测");
    }

    /** 清掉测试数据 */
    @Test
    @Order(3)
    @DisplayName("清理压测数据")
    void cleanTestData() {
        cleanLeftovers();
        printRedisState();
    }

    /** 第一部分：KEYS 和 SCAN 单次耗时随库大小的变化 */
    @Test
    @Order(1)
    @DisplayName("第一部分：KEYS 与 SCAN 的单次耗时")
    void keysVsScanLatency() {
        cleanLeftovers();
        System.out.println("\n========== 第一部分：单次操作耗时 ==========");
        System.out.println("（单位微秒，1ms = 1000µs，已剔除 " + WARMUP_ROUNDS + " 轮预热）");
        System.out.printf("%-10s %-8s %-24s %-24s%n", "库大小", "匹配数", "KEYS(1条命令)", "SCAN(分N批)");

        double usPerKey = 0;
        long lastDbsize = 0;
        for (int n : SCALES) {
            seed(n);
            try {
                long dbsize = worker.dbsize();
                Stats k = benchKeys();
                Stats s = benchScan();
                System.out.printf("%-10d %-8d min=%-5s p50=%-5s p99=%-5s 往返%d次%n",
                        dbsize, k.found, us(k.minNs), us(k.p50Ns), us(k.p99Ns), k.roundTrips);
                System.out.printf("%-10s %-8d min=%-5s p50=%-5s p99=%-5s 往返%d次%n",
                        "", s.found, us(s.minNs), us(s.p50Ns), us(s.p99Ns), s.roundTrips);
                usPerKey = k.p50Ns / 1000.0 / Math.max(dbsize, 1);
                lastDbsize = dbsize;
                System.out.printf("    边际成本 ≈ %.2f µs/key%n", usPerKey);
            } finally {
                clear(n);
            }
        }
        System.out.printf("%n外推：按最大规模(%d 个 key)算，100 万 key 时单次 KEYS 约 %.2f 秒%n",
                lastDbsize, usPerKey);
    }

    /** 第二部分：KEYS 执行期间，另一个连接的 PING 被拖慢多少（重点） */
    @Test
    @Order(2)
    @DisplayName("第二部分：KEYS 执行期间的阻塞效应")
    void keysBlocksOtherClients() throws Exception {
        cleanLeftovers();
        int n = SCALES[SCALES.length - 1];
        System.out.println("\n========== 第二部分：阻塞效应 ==========");
        System.out.println("连接 A 一直跑清理，连接 B 一直 PING，看 B 被拖到什么程度");
        System.out.println("规模：" + n + " 个 key，采样 " + BLOCK_WINDOW_MS + "ms\n");

        seed(n);
        try {
            long[] baseline = probeLatencies(BLOCK_WINDOW_MS, null);
            long[] underKeys = probeLatencies(BLOCK_WINDOW_MS, () -> worker.keys(MATCH_PATTERN));
            long[] underScan = probeLatencies(BLOCK_WINDOW_MS, RedisKeysVsScanBenchmarkTest::fullScanOnce);

            System.out.printf("%-20s %-8s %-8s %-8s %-8s%n", "场景", "采样数", "p50", "p99", "max");
            printBlock("空闲（无负载）", baseline);
            printBlock("A 循环执行 KEYS", underKeys);
            printBlock("A 循环执行 SCAN", underScan);

            long baseP99 = Math.max(pct(baseline, 0.99), 1);
            long keysP99 = pct(underKeys, 0.99);
            long scanP99 = pct(underScan, 0.99);
            System.out.printf("%n空闲 p99 = %s%n", us(baseP99));
            System.out.printf("KEYS 期间 p99 = %s（劣化 %.0f 倍）%n", us(keysP99), (double) keysP99 / baseP99);
            System.out.printf("SCAN 期间 p99 = %s（劣化 %.0f 倍）%n", us(scanP99), (double) scanP99 / baseP99);
            System.out.println("同样的清理工作，SCAN 对旁路请求的影响比 KEYS 小");
        } finally {
            clear(n);
        }
    }

    private static void printRedisState() {
        List<String> all = worker.keys(MATCH_PATTERN);
        int bench = 0;
        for (String key : all) {
            if (key.startsWith(BENCH_KEY_PREFIX)) bench++;
        }
        System.out.println("匹配 " + MATCH_PATTERN + " 的 key 数 = " + all.size()
                + "，其中测试数据 = " + bench
                + "，DBSIZE = " + worker.dbsize());
    }

    private static void seedWithPrefix(String prefix, int count) {
        Map<String, String> batch = new LinkedHashMap<>(SEED_BATCH_SIZE * 2);
        for (int i = 1; i <= count; i++) {
            batch.put(prefix + i, "v");
            if (batch.size() >= SEED_BATCH_SIZE) {
                worker.mset(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) worker.mset(batch);
    }

    /** 生产同款：一条 KEYS 命令拿全部匹配 key */
    private static Stats benchKeys() {
        long[] ns = new long[MEASURE_ROUNDS];
        int found = 0;
        for (int i = 0; i < WARMUP_ROUNDS; i++) worker.keys(MATCH_PATTERN);
        for (int i = 0; i < MEASURE_ROUNDS; i++) {
            long t0 = System.nanoTime();
            List<String> keys = worker.keys(MATCH_PATTERN);
            ns[i] = System.nanoTime() - t0;
            found = keys.size();
        }
        return new Stats(ns, found, 1);
    }

    /** 优化后：SCAN 游标迭代直到遍历完 */
    private static Stats benchScan() {
        long[] ns = new long[MEASURE_ROUNDS];
        int found = 0;
        int roundTrips = 0;
        for (int i = 0; i < WARMUP_ROUNDS + MEASURE_ROUNDS; i++) {
            int trips = 0;
            int cnt = 0;
            long t0 = System.nanoTime();
            KeyScanCursor<String> cursor = worker.scan(scanArgs());
            trips++;
            cnt += cursor.getKeys().size();
            while (!cursor.isFinished()) {
                cursor = worker.scan(cursor, scanArgs());
                trips++;
                cnt += cursor.getKeys().size();
            }
            long t1 = System.nanoTime();
            if (i >= WARMUP_ROUNDS) {
                ns[i - WARMUP_ROUNDS] = t1 - t0;
                found = cnt;
                roundTrips = trips;
            }
        }
        return new Stats(ns, found, roundTrips);
    }

    private static void fullScanOnce() {
        KeyScanCursor<String> cursor = worker.scan(scanArgs());
        while (!cursor.isFinished()) {
            cursor = worker.scan(cursor, scanArgs());
        }
    }

    private static ScanArgs scanArgs() {
        return ScanArgs.Builder.matches(MATCH_PATTERN).limit(SCAN_COUNT);
    }

    /** 在时间窗口内，一边跑 workload，一边用探针连接测 PING 延迟 */
    private static long[] probeLatencies(long windowMs, Runnable workload) throws InterruptedException {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        Thread workloadThread = null;
        if (workload != null) {
            workloadThread = new Thread(() -> {
                while (!stop.get()) workload.run();
            }, "workload");
            workloadThread.setDaemon(true);
            workloadThread.start();
        }

        Thread probeThread = new Thread(() -> {
            while (!stop.get()) {
                long t0 = System.nanoTime();
                probe.ping();
                latencies.add(System.nanoTime() - t0);
            }
        }, "probe");
        probeThread.setDaemon(true);
        probeThread.start();

        Thread.sleep(windowMs);
        stop.set(true);
        probeThread.join(5000);
        if (workloadThread != null) workloadThread.join(5000);

        long[] arr = new long[latencies.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = latencies.get(i);
        Arrays.sort(arr);
        return arr;
    }

    /** 灌 n 个测试 key，用 MSET 分批减少网络往返 */
    private static void seed(int n) {
        Map<String, String> batch = new LinkedHashMap<>(SEED_BATCH_SIZE * 2);
        for (int i = 1; i <= n; i++) {
            batch.put(BENCH_KEY_PREFIX + i, "v");
            if (batch.size() >= SEED_BATCH_SIZE) {
                worker.mset(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) worker.mset(batch);
    }

    /** 按确切 key 名分批删，不按模式删，避免误删真实缓存 */
    private static void clear(int n) {
        List<String> batch = new ArrayList<>(SEED_BATCH_SIZE * 2);
        for (int i = 1; i <= n; i++) {
            batch.add(BENCH_KEY_PREFIX + i);
            if (batch.size() >= SEED_BATCH_SIZE) {
                worker.del(batch.toArray(new String[0]));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) worker.del(batch.toArray(new String[0]));
    }

    private static void cleanLeftovers() {
        int deleted = cleanByPrefix(BENCH_KEY_PREFIX) + cleanByPrefix(OTHER_KEY_PREFIX);
        if (deleted > 0) System.out.println("已清理残留测试键: " + deleted + " 个");
    }

    private static int cleanByPrefix(String prefix) {
        ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(500);
        KeyScanCursor<String> cursor = worker.scan(args);
        int deleted = 0;
        while (true) {
            List<String> keys = cursor.getKeys();
            if (!keys.isEmpty()) {
                worker.del(keys.toArray(new String[0]));
                deleted += keys.size();
            }
            if (cursor.isFinished()) break;
            cursor = worker.scan(cursor, args);
        }
        return deleted;
    }

    /** 把纳秒统计汇总成 min / p50 / p99 */
    private static final class Stats {
        final long minNs, p50Ns, p99Ns;
        final int found, roundTrips;

        Stats(long[] raw, int found, int roundTrips) {
            long[] sorted = raw.clone();
            Arrays.sort(sorted);
            this.minNs = sorted[0];
            this.p50Ns = pct(sorted, 0.5);
            this.p99Ns = pct(sorted, 0.99);
            this.found = found;
            this.roundTrips = roundTrips;
        }
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private static String us(long ns) {
        return String.format("%.0fµs", ns / 1000.0);
    }

    private static void printBlock(String label, long[] sorted) {
        System.out.printf("%-20s %-8d %-8s %-8s %-8s%n",
                label, sorted.length, us(pct(sorted, 0.5)), us(pct(sorted, 0.99)), us(pct(sorted, 1.0)));
    }
}
