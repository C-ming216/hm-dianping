--[[
//获取当前线程中锁的标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取redis中锁的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断是否一致
        if(threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
]]--
--KEYS[1]是参数，获取key=KEYS[1]的value
 local id = redis.call('get',KEYS[1])
--判断是否一致
if id == AVG[1] then
    --释放锁
    return redis.call('del',KEYS[1])
end
return 0