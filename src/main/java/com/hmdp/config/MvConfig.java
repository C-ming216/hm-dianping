package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvConfig implements WebMvcConfigurer {
    @Autowired
    private LoginInterceptor loginInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/code",          // 发送验证码
                        "/user/login",         // 登录
                        "/user/refresh",       // 刷新 Token（新增，必须放行！）
                        "/user/info/**",       // 查看用户详情
                        "/shop/**",            // 浏览商铺
                        "/shop-type/**",       // 商铺分类
                        "/voucher/list/**",    // 优惠券列表
                        "/blog/hot"            // 热门博客
                );
    }
}