package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.hmdp.entity.User;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    IUserService userService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public void saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        save(blog);
    }

    @Override
    public List<Blog> queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return records;
    }

    @Override
    public List<Blog> queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        return page.getRecords();
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        String userId = UserHolder.getUser().getId().toString();
        //2.判断是否已经点赞--ZSet
        /*
        KEY:   blog:liked:{blogId}          ← 每篇笔记一个 key，笔记维度聚合
        TYPE:  ZSET
        VALUE(member): userId               ← 谁点的赞
        SCORE: 点赞时刻的时间戳              ← 什么时候点的
        */
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //获取这篇博客id下这个用户的点赞时间戳，如果未null，则没点赞
        Double score = stringRedisTemplate.opsForZSet().score(key,userId);
        //3.1如果没赞过，那么点赞
        if(score == null){
            boolean ok = update().setSql("liked = liked + 1").eq("id",id).update();
            if(ok){
                stringRedisTemplate.opsForZSet().add(key,userId,System.currentTimeMillis());
            }
        }
        //3.2如果赞过，那么取消点赞
        else {
            // 3b. 赞过 → 取消：数据库 liked-1，成功后 ZREM 移除
            boolean ok = update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            if (ok) {
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询笔记
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 2. 回填作者信息
        queryBlogUser(blog);
        // 3. 回填当前用户是否点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryBLogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //opsForZSet().range() 返回的就是 Set<V>
        Set<String> top5Ids = stringRedisTemplate.opsForZSet().range(key,0,4);
        if (top5Ids == null || top5Ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2. 把 userId 字符串转 Long
        List<Long> ids = top5Ids.stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());
        //拿到了userId去数据库查询完整用户数据（redis存的只是userId）
        Map<Long,User> userMap = userService.listByIds(ids)
                .stream().collect(Collectors.toMap(
                User::getId,user -> user
        ));
        List<UserDTO> userDTOS = ids.stream()
                .map(userMap::get)
                //过滤空值
                .filter(Objects::nonNull)
                //将User转化为UserDTO
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        //返回List<UserDTO>
        return Result.ok(userDTOS);
    }

    //回填笔记作者昵称和头像
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void isBlogLiked(Blog blog){
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }
}
