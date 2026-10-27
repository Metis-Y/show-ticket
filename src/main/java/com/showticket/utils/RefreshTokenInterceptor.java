package com.showticket.utils;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.showticket.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.showticket.utils.RedisConstants.LOGIN_USER_KEY;
import static com.showticket.utils.RedisConstants.LOGIN_USER_TTL;


//第二层拦截
public class RefreshTokenInterceptor implements HandlerInterceptor {
    //获取redis操作对象
    private StringRedisTemplate stringRedisTemplate;
    //因为不在ioc容器里，不能用注解得到StringRedisTemplate
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //判断用户是否合法登录，创建带变量的线程
        String token = request.getHeader( "authorization");
        if (StringUtils.isBlank(token)) {
            return true;
        }
        String key=LOGIN_USER_KEY + token;
        //Object user = session.getAttribute("user");
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()){
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
        stringRedisTemplate.expire(key,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //存在，保存用户信息到localThread---随时要用
        UserHolder.saveUser(userDTO);
        return true;
    }

}
