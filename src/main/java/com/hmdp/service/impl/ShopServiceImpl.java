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

import java.io.Serializable;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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

    StringRedisTemplate stringRedisTemplate;
    public ShopServiceImpl(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public Result queryShopById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.查询redis钟是否有该商铺
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.如果有，则直接返回
        if(StrUtil.isNotBlank(shopJson)){//存在返回true
            //反序列化并返回
            return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
        }
        //3.如果不存在，则在数据库中查询
        Shop shop = getById(id);
        //4.查询返回结果写入redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        //5.返回shop
        return Result.ok(shop);

    }


}
