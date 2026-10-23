package com.jiaoyu.user_api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// JWT 工具类：负责“生成令牌”和“解析/校验令牌”
@Component
public class JwtUtil {

    // 签名密钥（从 application.properties 读取）。jjwt 要求密钥长度 >= 256 bit
    @Value("${app.jwt.secret}")
    private String secret;

    // 令牌有效期（毫秒），从配置文件读取
    @Value("${app.jwt.expiration}")
    private long expirationMs;

    // 把字符串密钥转成 jjwt 需要的 SecretKey 对象
    // 注意：secret 是明文长字符串，直接用 UTF-8 字节即可（长度需 >= 32 字节）
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // 根据用户名为其生成一个 JWT（RealWorld 规范里叫做 Token）
    public String generateToken(String username) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)                       // 把用户名写进令牌
                .issuedAt(now)                            // 签发时间
                .expiration(new Date(now.getTime() + expirationMs)) // 过期时间
                .signWith(getSigningKey())                // 用密钥签名
                .compact();
    }

    // 从令牌中解析出用户名
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    // 校验令牌是否合法（用户名要对得上，且没过期）
    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isExpired(token);
    }

    private boolean isExpired(String token) {
        return parseClaims(token).getExpiration().before(new Date());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
