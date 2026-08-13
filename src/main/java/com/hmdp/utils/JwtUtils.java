package com.hmdp.utils;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import com.hmdp.config.JwtProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component

public class JwtUtils {
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_JTI = "jti";
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";


    @Resource
    private JwtProperties jwtProperties;

    /** 解析并校验后的载荷 */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class JwtPayload{
        private Long userId;
        private String jti;
    }

    //生成Access Token
    public String createAccessToken(Long userId, String jti){
        // 1. 从配置中获取 Access Token 的有效期（单位通常是秒或分钟），并将其转换为毫秒
        long ttlMills = jwtProperties.getAccessTtl()* 60 * 1000L;
        return createToken(userId,jti,TYPE_ACCESS,ttlMills);
    }
    //生成Refresh Token
    public String createRefreshToken(Long userId, String jti){
        long ttlMills = jwtProperties.getRefreshTtl() * 24 * 60 * 60 * 1000L;
        return createToken(userId,jti,TYPE_REFRESH,ttlMills);
    }

    //私有核心方法：负责构建和签发 JWT 字符串
    /**
     * 私有核心方法：负责构建和签发 JWT 字符串
     * @param userId 用户ID
     * @param jti Token的唯一标识
     * @param type Token类型 (ACCESS 或 REFRESH)
     * @param ttlMillis 有效期时长（毫秒）
     */
    private String createToken(Long userId,String jti,String type,long ttlMillis){
        // 1. 使用 hutool 的 JWT 构建器模式开始创建 Token
        return JWT.create()
                // 2. 设置载荷(Payload)：存入业务数据 "userId"，用于后续识别是哪个用户
                .setPayload(CLAIM_USER_ID, userId)
                // 3. 设置载荷(Payload)：存入 "jti"，作为 Token 的唯一身份证号（用于黑名单/注销）
                .setPayload(CLAIM_JTI, jti)
                // 4. 设置载荷(Payload)：存入 "type"，标记这是 Access 还是 Refresh Token
                .setPayload(CLAIM_TYPE, type)
                // 5. 设置载荷(Payload)：存入 "iss" (Issuer)，标记签发者是谁（防止跨服务盗用）
                .setPayload("iss", jwtProperties.getIssuer())
                // 6. 设置标准声明 "iat" (Issued At)：记录 Token 的签发时间（当前时间）
                .setIssuedAt(new Date())
                // 7. 设置标准声明 "exp" (Expiration Time)：计算并设置过期时间点
                //    当前系统时间 + 传入的有效期时长 = 绝对过期时间
                .setExpiresAt(new Date(System.currentTimeMillis() + ttlMillis))
                // 8. 设置签名密钥 (Key)
                //    从配置中获取密钥字符串，转为字节数组。这是防止 Token 被篡改的核心钥匙
                .setKey(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8))
                // 9. 执行签名操作并构建最终的 String 字符串
                //    这一步会将 Header、Payload 编码并用密钥签名，生成最终的 JWT
                .sign();
    }


    /*
     * 校验令牌：验签 + 过期检查 + 类型检查，全部通过返回载荷，否则返回 null。
     * 注意：此处只做 JWT 自身校验，不查 Redis（Redis 白名单校验由调用方/拦截器负责）。
     */
    public  JwtPayload parseAndVerify(String token, String expectedType){
        try {
            //将一个JWT 字符串（通常由客户端通过 HTTP Header 携带，如 Authorization: Bearer xxx）解析成一个 JWT 对象
            JWT jwt = JWTUtil.parseToken(token);
            // 1. 验签：密钥不一致/被篡改则 verify() 返回 false
            /* 链式调用拆解：
             *   jwtProperties.getSecret()          → 从配置中读取服务器私钥字符串
             *   .getBytes(StandardCharsets.UTF_8) → 转为 UTF-8 字节数组（签名算法需要字节输入）
             *   jwt.setKey(...)                    → 将密钥设置到 JWT 对象中，准备验签
             *   .verify()                          → 用密钥重新计算签名，与 Token 中的签名对比
             *   !...                               → 若验签失败（返回 false），说明 Token 被篡改或伪造，拒绝请求
             */
            /* JWT 验签原理（无需查询数据库）：
             *   1. 从客户端 Token 中解析出 Header + Payload + Signature（签名）
             *   2. 用服务器密钥 + Header + Payload，重新计算一次签名
             *   3. 将重新计算的签名 与 Token 中自带的签名做数学对比
             *   4. 完全一致 → Token 可信；不一致 → 被篡改或伪造，返回 null
             *   本质：纯数学运算（HMAC-SHA256），只要密钥相同且内容未被修改，签名必然一致
             */
            if (!jwt.setKey(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)).verify()) {
                return null;
            }
            // 2. 过期校验：exp 已过则抛异常
            JWTValidator.of(jwt).validateDate();

            // 3. 类型校验：access 令牌不能当 refresh 用，反之亦然
            String type = jwt.getPayload(CLAIM_TYPE).toString();
            if (!expectedType.equals(type)) {
                return null;
            }
            Long userId = Long.valueOf(jwt.getPayload(CLAIM_USER_ID).toString());
            String jti = jwt.getPayload(CLAIM_JTI).toString();
            //解析成功，返回userid和jti
            return new JwtPayload(userId, jti);
        } catch (Exception e) {
            // 解析失败 / 验签失败 / 过期 / 类型不符，统一返回 null
            return null;
        }
    }

}