package com.jiaoyu.user_api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// Spring Security 的总配置：哪些接口要登录、怎么校验 Token、密码怎么加密
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 关闭 CSRF（我们是无状态 JWT 接口，不需要它）
            .csrf(csrf -> csrf.disable())
            // 允许跨域（前端页面调接口时需要）
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // 无状态：服务端不保存会话，每次请求都带 Token
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 配置接口的访问规则（首条命中即生效，顺序即优先级）
            .authorizeHttpRequests(auth -> auth
                // 注册、登录接口完全公开
                .requestMatchers(HttpMethod.POST, "/users", "/users/login").permitAll()
                // ★ 关注流必须登录：必须写在 /articles 放行规则之前，否则会被下面的规则吃掉
                .requestMatchers(HttpMethod.GET, "/articles/feed").authenticated()
                // 浏览文章与评论列表：用精确匹配放行。
                // 这里刻意不用 "/articles/**"——Ant 风格的多段通配会把 /articles/feed 一起放行；
                // 单段 "*" 只匹配一层：/articles、/articles/{slug}、/articles/{slug}/comments，
                // 不会匹配 /articles/feed，也不会匹配 /articles/x/comments/1/like（写操作仍需登录）。
                .requestMatchers(HttpMethod.GET, "/articles", "/articles/*", "/articles/*/comments").permitAll()
                // 查看他人资料与标签：GET 公开
                .requestMatchers(HttpMethod.GET, "/profiles/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/tags", "/tags/hot").permitAll()
                // 放行错误页：否则接口抛异常时会变成误导性的 403，看不到真实错误
                .requestMatchers("/error").permitAll()
                // 其余接口（发文/改/删/收藏/评论/关注等写操作）必须携带有效 Token
                .anyRequest().authenticated()
            )
            // 匿名访问受保护端点返回 401（而不是 Spring Security 默认的 403），前端只需判 status===401
            .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"errors\":{\"body\":[\"未登录或登录已过期\"]}}");
            }))
            // 把我们的 JWT 过滤器放在用户名密码过滤器之前
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 密码加密器：BCrypt 是行业标准的密码哈希算法，绝不存明文
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 认证管理器（Spring Security 内部使用）
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // CORS 配置：允许任意来源访问（学习阶段方便，生产要收紧）
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
