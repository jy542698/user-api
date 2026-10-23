package com.jiaoyu.user_api.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 过滤器：每个请求进来时，从 Authorization 头里取出 Token 并校验，
// 校验通过就把“当前用户”放进 Spring Security 的上下文，后续接口就能拿到是谁在访问。
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserDetailsServiceImpl userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");

        // RealWorld 规范要求请求头写成：Authorization: Token xxxxxxx
        if (authHeader == null || (!authHeader.startsWith("Token ") && !authHeader.startsWith("Bearer "))) {
            filterChain.doFilter(request, response);
            return;
        }

        // 去掉前缀，拿到纯 Token 字符串
        final String token = authHeader.startsWith("Token ")
                ? authHeader.substring(6).trim()
                : authHeader.substring(7).trim();

        try {
            final String username = jwtUtil.extractUsername(token);

            // 如果用户名存在，且当前安全上下文里还没有认证信息，就做一次认证
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtUtil.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException ex) {
            // 关键修复（D12）：token 过期 / 被篡改 / 格式非法 / 用户已删除时，这里绝不能把异常抛出去。
            // 老代码没捕获 → 任何带过期 token 的请求都 500，连公开的 GET /articles 都打不开。
            // 现在的语义：清空上下文后继续放行——公开接口照常返回 200，受保护接口由
            // SecurityConfig 的 authenticationEntryPoint 统一给 401。
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
