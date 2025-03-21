package com.example.lgygateway.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;

import javax.crypto.SecretKey;
import java.util.Date;

public class JwtUtil {
    private static final String SECRET_KEY = "your-256-bit-secret-key-here-1234567890";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET_KEY.getBytes());

    /**
     * 生成JWT令牌（用户登录时调用）
     */
    public static String generateToken(String userId) {
        return Jwts.builder()
                .setSubject(userId) // 用户ID存储在Subject中
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600_000)) // 1小时过期
                .signWith(KEY)
                .compact();
    }

    /**
     * 从请求头解析JWT并提取用户ID
     */
    public static String extractUserId(FullHttpRequest request) {
        String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (validateToken(token)) {
                return Jwts.parserBuilder()
                        .setSigningKey(KEY)
                        .build()
                        .parseClaimsJws(token)
                        .getBody().getSubject();
            }
        }
        return ""; // 未认证用户返回空
    }

    /**
     * 验证JWT有效性
     */
    public static boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(KEY)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (JwtException e) {
            Log.logger.error("JWT验证失败: {}", e.getMessage());
            return false;
        }
    }
}
