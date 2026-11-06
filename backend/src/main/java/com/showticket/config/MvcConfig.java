package com.showticket.config;

import com.showticket.utils.LoginInterceptor;
import com.showticket.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


/**
 * Web MVC 配置：注册登录相关拦截器
 *
 * 两个拦截器靠 order 决定执行顺序，这点很容易混淆：
 * 1. order=0 RefreshTokenInterceptor 先执行：解析 token、把用户放进 ThreadLocal、给 token 续期，
 *    无论登录与否都放行（它不拦人）；
 * 2. order=1 LoginInterceptor 后执行：只看 ThreadLocal 里有没有用户，没有就返回 401。
 * 所以需要登录的接口不需要自己判定登录态，白名单接口通过 LoginInterceptor 的 excludePathPatterns 跳过。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    // 拦截器是 new 出来的，不在 IOC 容器里，所以在这里把 Bean 传进去
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry){
        // 后执行：登录拦截（order=1）。下面 excludePathPatterns 里的是允许游客访问的接口
        registry.addInterceptor(new LoginInterceptor())
                    .excludePathPatterns(
                            "/venue/**",
                            "/show/**",
                            "/show-type/**",
                            "/upload/**",
                            "/review/hot",
                            "/user/code",
                            "/user/login",
                            // Knife4j 在线接口文档。Spring 6 的 PathPattern 下 /v3/api-docs/**
                            // 不再匹配不带尾斜杠的 /v3/api-docs 本身，需要显式列出
                            "/doc.html",
                            "/webjars/**",
                            "/v3/api-docs",
                            "/v3/api-docs/**",
                            "/favicon.ico"
        ).order(1);


        // 先执行：token 刷新（order=0），/user/code 和 /user/login 本身不需要 token
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**")
                .excludePathPatterns(
                    "/user/code",
                    "/user/login").order(0);
    }
}
