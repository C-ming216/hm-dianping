package com.hmdp.utils;

import com.hmdp.dto.Result;
 import org.springframework.core.io.ClassPathResource;
 import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/*
* 个人思考与总结
利用redis的set lock thread1 NX EX 10来保证分布式线程安全问题，key存储KEY_PREFIX（标识这是一个锁） + name（例如user）；
value存储threadId，threadId = ID_PREFIX（UUID） + Thread.currentThread().getId();这是锁的唯一标识。
当获取锁的时候，这些锁的数据会存储进redis中，只有获取成功时才会执行业务代码
* */

public class SimpRedisLock implements ILock{

    private final StringRedisTemplate stringRedisTemplate;
    //锁的名称
    private final String name;

    public SimpRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    //即使在不同的jvm中，一个线程也只能获取一个uuid，主要用来区分不同的jvm
    private static final String ID_PREFIX = UUID.randomUUID()+ "-";
    //创建并声明一个lua脚本对象并指定脚本的返回值类型
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>();
    //初始化脚本
    static {
        // 第一步：设置 Lua 脚本的存放位置（classpath 下的 unlock.lua 文件）
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));

        // 第二步：设置返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * 尝试获取锁
     * @param timeoutSec 锁的超时时间，单位：秒，超市自动删除
     * @return 是否获取成功
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识--为什么要获取线程标识用作锁的值--唯一标识一把锁，防止误删锁--但是当集群模式下，多个jvm可能会用id冲突情况，故用UUID标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁-用业务名称来命名，一个业务有多把锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId +"",timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        /*

        获取当前线程中锁的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取redis中锁的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否一致
        if(threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
        //如果不一致则锁继续存在，知道超时删除

        */
        stringRedisTemplate.execute(
                //参数1：lua脚本对象
                UNLOCK_SCRIPT,
                //参数2：KEYS[1]
                Collections.singletonList(KEY_PREFIX + name),
                // 参数3：ARGV[1]
                ID_PREFIX + Thread.currentThread().getId()
        );

    }
}
