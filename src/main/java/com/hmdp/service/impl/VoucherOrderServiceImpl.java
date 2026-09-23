package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private redisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final String STREAM_KEY = RedisConstants.SECKILL_STREAM_KEY; // stream.orders
    private static final String GROUP = "g1";    // 消费者组
    private static final String CONSUMER = "c1"; // 消费者
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 事务代理对象：请求线程中赋值，异步消费线程中使用（volatile 保证跨线程可见）
    private volatile IVoucherOrderService proxy;

    /** 应用启动后：确保 Stream 与消费者组存在，然后开启常驻消费线程 */
    @PostConstruct
    private void init() {
        try {
            // 1.确保 Stream 存在（XGROUP CREATE 要求 key 必须已存在）：
            //    不存在时先放一条引导消息建流，消费端遇到没有 userId 字段的消息会 ACK 后丢弃
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(STREAM_KEY))) {
                Map<Object, Object> bootstrap = new HashMap<>();
                bootstrap.put("bootstrap", "1");
                stringRedisTemplate.opsForStream().add(STREAM_KEY, bootstrap);
            }
            // 2.从 0 开始创建消费者组（保证组创建之前产生的消息也能被消费）；已存在则抛 BUSYGROUP，捕获忽略
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP);
            log.info("消费者组 {} 创建成功", GROUP);
        } catch (Exception e) {
            log.info("消费者组 {} 已存在，跳过创建", GROUP);
        }
        // 3.启动消费线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @Override
    public Result secKillVoucher(Long voucherId) {
        // 1.获取用户与订单号
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 2.执行 Lua 脚本：判库存+判一人一单+预扣库存+记录用户+发 Stream 消息（原子）
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId));
        // 3.判断结果：0 成功 / 1 库存不足 / 2 重复下单 / -1 未预热
        if (result == null || result != 0) {
            String msg = (result != null && result == 1) ? "库存不足"
                    : (result != null && result == 2) ? "不允许重复下单"
                    : "秒杀券不存在或未预热";
            return Result.fail(msg);
        }
        // 4.获取代理对象，供异步消费线程调用事务方法
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 5.立即返回订单号（落库由消费者异步完成）
        return Result.ok(orderId);
    }

    /** 消费者任务：读 Stream 新消息 → 落库 → ACK；异常消息留在 PEL，转 handlePendingList 重试 */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP, CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                    if (list == null || list.isEmpty()) {
                        continue; // 没有新消息，继续循环
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    if (!value.containsKey("userId")) {
                        // 引导消息/非法消息：ACK 后丢弃
                        stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                        continue;
                    }
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 处理成功，手动 ACK
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                } catch (Exception e) {
                    log.error("消费订单异常，转入 pending 队列处理", e);
                    handlePendingList();
                }
            }
        }

        /** 读取 pending 队列（已投递未 ACK 的消息，含宕机前未处理完的订单），直到清空 */
        private void handlePendingList() {
            while (true) {
                try {
                    // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP, CONSUMER),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_KEY, ReadOffset.from("0")));
                    if (list == null || list.isEmpty()) {
                        break; // pending 已清空，回到主循环
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理 pending 订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** 落库前加 Redisson 锁：按用户粒度防并发写库（单消费线程下是双保险，多实例时是必需品） */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        if (proxy == null) {
            // 防御：此时消息未 ACK 会留在 pending 中被重试，届时 proxy 已就绪
            throw new IllegalStateException("事务代理对象尚未就绪");
        }
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单，用户：{}", userId);
            return;
        }
        try {
            // 必须走代理调用，this.createVoucher() 事务不生效
            proxy.createVoucher(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /** 事务方法：一人一单校验 + 乐观锁扣库存 + 写订单表（userId 从消息体取，异步线程无 ThreadLocal） */
    @Override
    @Transactional
    public Result createVoucher(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1.一人一单（数据库层校验）
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            log.error("用户已购买过，用户：{}，券：{}", userId, voucherId);
            return Result.fail("您已购买过");
        }
        // 2.乐观锁扣库存（stock > 0 才扣，防超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足，券：{}", voucherId);
            return Result.fail("库存不足");
        }
        // 3.写订单表
        save(voucherOrder);
        log.debug("订单落库成功，orderId：{}", voucherOrder.getId());
        return Result.ok(voucherOrder.getId());
    }
}
