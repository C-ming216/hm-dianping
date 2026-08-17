package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private final StringRedisTemplate stringRedisTemplate;

    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 查询商铺（对外入口）
     * 职责：参数校验 + 调用业务方法 + 把结果包装成 Result
     */
    @Override
    public Result queryShopById(Long id) {
        // 参数校验：非法 id 直接拒绝，不打缓存不打库（防穿透第一道防线）
        if (id == null || id <= 0) {
            return Result.fail("店铺id不合法");
        }
        // 互斥锁防击穿（内部复用防穿透的空值缓存逻辑）
        Shop shop = queryShopWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 防穿透：缓存空值
     * 返回业务对象 Shop，null 表示店铺不存在（null 是"查不到"的通用约定，与 getById 一致）
     * 被互斥锁方法复用：抢到锁后调用它完成"double-check + 查库 + 写缓存"
     */
    private Shop queryShopWithPassthrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 命中正常数据
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 3. key 存在但值为空 = 命中"空值标记" → 数据不存在，直接返回，不再查库（防穿透关键）
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return null;
        }
        // 4. 缓存未命中 → 查数据库
        Shop shop = getById(id);
        // 5. 数据库也不存在 → 缓存空值，TTL 短（2 分钟），防穿透
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 有数据 → 写缓存，正常 TTL（30 分钟）
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 防击穿：互斥锁
     * 同一时刻只允许一个线程查库重建缓存，其余线程拿锁失败后休眠重试
     * 抢到锁后复用防穿透方法完成"double-check + 查库 + 写缓存"，避免重复代码（DRY）
     */
    private Shop queryShopWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 先查缓存，命中直接返回（缓存有效时不需要抢锁）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return null; // 命中空值标记
        }
        // 2. 缓存未命中 → 抢锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = false;
        try {
            locked = tryLock(lockKey);
            if (!locked) {
                // 3. 没抢到：说明别人正在重建缓存，休眠 50ms 后递归重试
                Thread.sleep(50);
                return queryShopWithMutex(id);
            }
            // 4. 抢到锁：调用防穿透方法（其内部第一步会再查一次缓存 = double-check，
            //    等待期间别人若已重建缓存则直接返回，否则查库 + 写缓存）
            return queryShopWithPassthrough(id);
        } catch (InterruptedException e) {
            // 恢复中断状态，再转成运行时异常向上抛（不吞掉中断信号）
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询商铺被中断", e);
        } finally {
            // 5. 只有抢到锁的线程才释放锁（防止误删别人的锁）
            if (locked) {
                unlock(lockKey);
            }
        }
    }

    /**
     * 获取互斥锁：Redis SET key value NX EX（原子操作）
     * 返回 true = 抢到锁；false = 别人持有
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); // 防 NPE：setIfAbsent 可能返回 null
    }

    /**
     * 释放互斥锁：删除 key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 先更新数据库
        updateById(shop);
        // 2. 再删缓存（保证下次请求重建新缓存，避免脏数据）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
