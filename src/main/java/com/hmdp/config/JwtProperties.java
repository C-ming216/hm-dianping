package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp.jwt")
public class JwtProperties {
    /** 签名密钥：HS256 要求至少 256bit（32 字节），生产中务必使用随机长字符串 */
    private String secret;

    /** Access Token 有效期（分钟） */
    private Long accessTtl;

    /** Refresh Token 有效期（天） */
    private Long refreshTtl;

    /** 签发者 */
    private String issuer;
}
