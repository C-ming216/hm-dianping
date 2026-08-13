package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
@Component
public class LoginInterceptor implements HandlerInterceptor {
    //在拦截器中实现功能
    @Resource
    private JwtUtils jwtUtils;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.从request中获取session,再从session中获取用户//2.判断用户是否存在
//        UserDTO userDTO = (UserDTO) request.getSession().getAttribute("userDTO");
//        if(userDTO == null){
//            //拦截
//            //  --怎么拦截？return什么
//            response.setStatus(401);
//            return false;
//        }
//        //3.保存用户到ThreadLocal
//        UserHolder.saveUser(userDTO);
//        return HandlerInterceptor.super.preHandle(request, response, handler);



        //1.从请求头获取token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        // 2. 解析并校验 JWT（验签 + 过期 + 类型）
        JwtUtils.JwtPayload payload = jwtUtils.parseAndVerify(token, JwtUtils.TYPE_ACCESS);
        if (payload == null) {
            response.setStatus(401);
            return false;
        }
        // 3. 查 Redis 白名单：key 存在才有效（登出后 key 被删除 → 立即失效）
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_USER_KEY + payload.getJti());
        if (StrUtil.isBlank(json)) {
            response.setStatus(401);
            return false;
        }
        // 4. 反序列化用户并存入 ThreadLocal
        UserDTO userDTO = JSONUtil.toBean(json, UserDTO.class);
        UserHolder.saveUser(userDTO);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable Exception ex) throws Exception {
        UserHolder.removeUser();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
