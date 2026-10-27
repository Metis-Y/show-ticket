package com.showticket.config;

import com.showticket.utils.LoginInterceptor;
import com.showticket.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


@Configuration
public class MvcConfig implements WebMvcConfigurer {

    //这里在ioc容器里面，可以直接注入
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry){
            registry.addInterceptor(new LoginInterceptor())
                    .excludePathPatterns(
                            "/venue/**",
                            "/show/**",
                            "/show-type/**",
                            "/upload/**",
                            "/review/hot",
                            "/user/code",
                            "/user/login",
                            // Knife4j 在线接口文档
                            "/doc.html",
                            "/webjars/**",
                            "/v3/api-docs/**",
                            "/favicon.ico"
        ).order(1);


        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**")
                .excludePathPatterns(
                    "/user/code",
                    "/user/login").order(0);
    }
}
