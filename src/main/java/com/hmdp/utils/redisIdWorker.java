package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class redisIdWorker {
    private static final long INIT_TIMESTAMP = 1767225600;
    private static final long COUNT_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;

    public redisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //创建一个获取当前时间的对象
        LocalDateTime now = LocalDateTime.now();
        // 将当前时间转换为秒级时间戳（基于UTC时区）
        long currTime = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = currTime-INIT_TIMESTAMP;
        //获取当前日期
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增序列号
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date);

        //拼接
        return timestamp << COUNT_BITS | count;
    }


}