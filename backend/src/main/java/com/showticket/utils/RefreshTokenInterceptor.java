package com.showticket.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.showticket.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.showticket.utils.RedisConstants.LOGIN_USER_KEY;
import static com.showticket.utils.RedisConstants.LOGIN_USER_TTL;


/**
 * 登录态刷新拦截器（第一层，order=0）
 *
 * 它不负责拦截：没带 token、token 已失效都是直接放行，是否必须登录交给 LoginInterceptor 判断。
 * 它只做两件事：把登录用户放进 ThreadLocal 供本次请求使用，并给 token 续期（活联会话不过期）。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    // 拦截器由 MVC 配置 new 出来，不在 IOC 容器里，所以只能构造器手动传入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 没带 token 说明是游客请求，放行，后续由 LoginInterceptor 决定要不要拦
        String token = request.getHeader( "authorization");
        if (StringUtils.isBlank(token)) {
            return true;
        }
        String key=LOGIN_USER_KEY + token;
        // token 对应的 hash 已过期（或已登出），同样当游客放行
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()){
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
        // 每次请求都续期：30 分钟内有访问就一直有效，否则自然过期
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 存线程副本，请求结束后由 LoginInterceptor.afterCompletion 清理
        UserHolder.saveUser(userDTO);
        return true;
    }

}
