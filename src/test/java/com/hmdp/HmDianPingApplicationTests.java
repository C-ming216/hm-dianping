package com.hmdp;

import com.hmdp.utils.redisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    redisIdWorker redisIdWorker;
    private final ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        // 创建计数器，用于等待300个线程完成任务
        CountDownLatch latch = new CountDownLatch(300);

        // 定义任务：每个线程生成100个ID
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                // 调用Redis ID生成器获取唯一ID
                long id = redisIdWorker.nextId("order");
                // 打印生成的ID
                System.out.println("id = " + id);
            }
            // 当前线程任务完成，计数器减1
            latch.countDown();
        };

        // 记录开始时间
        long begin = System.currentTimeMillis();
        // 提交300个任务到线程池
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        // 关键：等待所有任务执行完成（否则主线程直接结束，线程池任务还没跑）
        latch.await();
        // 记录结束时间
        long end = System.currentTimeMillis();
        // 打印总耗时
        System.out.println("Time：" + (end - begin));
        // 关闭线程池（释放资源）
        es.shutdown();
    }

}