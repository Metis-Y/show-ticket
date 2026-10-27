package com.showticket.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.dto.LoginFormDTO;
import com.showticket.dto.Result;
import com.showticket.dto.UserDTO;
import com.showticket.entity.User;
import com.showticket.mapper.UserMapper;
import com.showticket.service.IUserService;
import com.showticket.utils.RegexUtils;
import com.showticket.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.showticket.utils.RedisConstants.*;
import static com.showticket.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    //redis操作工具
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    //校检手机号，发送验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {

        if(RegexUtils.isPhoneInvalid(phone)){

            return Result.fail("手机号格式错误");
        };

        String code = RandomUtil.randomNumbers(6);

        //设置验证码
        //session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.debug("发送验证码成功{}给{}", code, phone);

        return Result.ok();
    }

    //登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String code = loginForm.getCode();
        String phone=loginForm.getPhone();
        //String cacheCode = (String) session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        //校检验证码
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        //校检手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        };

        //mybatisplus查询方法
        User user=query().eq("phone", phone).one();

        //不存在
        if(user==null){
            user = createUserWithPhone(phone);
        }

        //保存UserDTO所需要的信息，避免返回敏感信息
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //用token令牌做key，需要生成token
        String token = UUID.randomUUID().toString();
        //要转换成hashmap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String,Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) ->
                                fieldValue == null ? null : fieldValue.toString()
                        ));
        //储存
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //设置令牌有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    //签到
    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();//2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,  dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));


        if (result == null || result.isEmpty()){
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while (true) {
            //6.1.让这个数字与1做与运算，得到数字的最后一个bit位 // 判断这个bit位是否为
            if ((num & 1) == 0) {
                // 如果为0，说明未到，结束
                break;
            } else {
                //如果不为0，说明已签到，计数器+1
                count++;
                //把数字右移一位，抛弃最后一个bit位，继续下一个bit位
                num >>>= 1;
            }
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
