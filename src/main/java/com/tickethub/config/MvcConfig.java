package com.tickethub.config;

import com.tickethub.interceptor.LoginInterceptor;
import com.tickethub.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * MVC 配置：注册登录拦截器 + 跨域配置（OSS 静态站点访问后端 API 必需）
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录校验拦截器（只拦需要登录的接口）
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/code",
                        "/upload/**",
                        "/show/**",
                        "/show-category/**",
                        "/tier/list/**",
                        "/post/hot",
                        "/error"
                ).order(1);
        // token 刷新拦截器（拦截所有请求，有 token 就续期）
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }

    /**
     * 跨域配置：前端部署在 OSS 域名下，与后端不同源，必须放行 CORS。
     * 生产环境建议把 allowedOriginPatterns 收敛为具体域名。
     */
    @Override
    public void addCorsMappings(org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
