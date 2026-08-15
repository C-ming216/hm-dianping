package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_LIST_TYPE;
import static com.hmdp.utils.RedisConstants.SHOP_LIST_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryShopType() {
        //1.查询商户类型是否在redis中
        String shopTypeJson = stringRedisTemplate.opsForValue().get(SHOP_LIST_TYPE);
        if(StrUtil.isNotBlank(shopTypeJson)){
            return JSONUtil.toList(shopTypeJson,ShopType.class);
        }
        //2.不存在则从数据库中查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //查询出的数据添加进缓存
        stringRedisTemplate.opsForValue().set(SHOP_LIST_TYPE,JSONUtil.toJsonStr(typeList),SHOP_LIST_TYPE_TTL, TimeUnit.MINUTES);

        return typeList;
    }
}
