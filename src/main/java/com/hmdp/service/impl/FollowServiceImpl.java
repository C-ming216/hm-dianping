package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;

    /**
     * @param followUserId 博主id
     *
     * */
    @Override
    public Result isFollowed(Long followUserId) {
        //1.查询当前登录用户的id
        Long userId = UserHolder.getUser().getId();
        //2.查询数据库关系是否存在
        boolean followed = query()
                .eq("user_id", userId)
                .eq("follow_user_id", followUserId)
                .exists();
        return Result.ok(followed);
    }

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        // 1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 0. 防御：不能关注自己（前端隐藏了按钮，后端也要防）
        if (followUserId.equals(userId)) {
            return Result.fail("不能关注自己");
        }
        String myKey = RedisConstants.FOLLOW_KEY + userId;

        if (isFollow) {
            // 2. 关注：先查是否已关注，防止重复插入
            boolean exists = query()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
                    .exists();
            if (exists) {
                return Result.fail("已关注该用户");
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean ok = save(follow);
            if (ok) {
                // 4. DB 成功后同步 Redis SET（先库后缓存）
                stringRedisTemplate.opsForSet().add(myKey, followUserId.toString());
            }
            return ok ? Result.ok() : Result.fail("关注失败");
        }else{
            // 4. 取关：删除关系记录，删除行数为 0（本来就没关注）也视为成功，保证幂等
            boolean ok = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (ok) {
                // 6. DB 成功后同步 Redis SET
                stringRedisTemplate.opsForSet().remove(myKey, followUserId.toString());
            }
            return ok ? Result.ok() : Result.fail("取关失败");
        }

    }

    @Override
    public Result queryCommonFollows(Long targetUserId) {
        Long userId = UserHolder.getUser().getId();
        String myKey = RedisConstants.FOLLOW_KEY + userId;
        String targetKey = RedisConstants.FOLLOW_KEY + targetUserId;


        // 2. 求交集：一条 SINTER 拿到双方都关注的人
        Set<String> intersectIds = stringRedisTemplate.opsForSet().intersect(myKey, targetKey);
        if (intersectIds == null || intersectIds.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 批量查用户并转 DTO
        List<Long> ids = intersectIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        Map<Long, User> userMap = userService.listByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
        List<UserDTO> userDTOS = ids.stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }
}
