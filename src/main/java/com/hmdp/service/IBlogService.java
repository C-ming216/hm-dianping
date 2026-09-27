package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    void saveBlog(Blog blog);
    List<Blog> queryHotBlog(Integer current);
    List<Blog> queryMyBlog(Integer current);

    Result likeBlog(Long id);

    Result queryBlogById(Long id);

    Result queryBLogLikes(Long id);
}
