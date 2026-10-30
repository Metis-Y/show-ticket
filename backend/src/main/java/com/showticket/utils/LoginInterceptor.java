package com.showticket.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.showticket.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录校验拦截器（第二层，order=1）
 *
 * 只读 ThreadLocal，不查 Redis：有用户就放行，没有就返回 401。
 * 用户信息是 RefreshTokenInterceptor 在它之前放进去的，所以判登录不花钱。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if (UserHolder.getUser() == null) {
            // 未登录：返回 401，前端据此跳登录页
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 必须清理：Tomcat 线程是复用的，不清理会让下个请求拿到上个请求的用户
        UserHolder.removeUser();
    }
}
