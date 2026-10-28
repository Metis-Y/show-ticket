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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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

    /** 验证码发送限流脚本：手机号冷却 + IP 小时配额，原子执行 */
    private static final DefaultRedisScript<Long> SEND_CODE_LIMIT_SCRIPT;

    static {
        SEND_CODE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        SEND_CODE_LIMIT_SCRIPT.setLocation(new ClassPathResource("send_code_limit.lua"));
        SEND_CODE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    //校检手机号，发送验证码
    @Override
    public Result sendCode(String phone, String clientIp) {

        if(RegexUtils.isPhoneInvalid(phone)){

            return Result.fail("手机号格式错误");
        };

        // 发送限流：同一手机号 60 秒一次，同一 IP 每小时最多 10 次
        Long limitResult = stringRedisTemplate.execute(
                SEND_CODE_LIMIT_SCRIPT,
                Arrays.asList(LOGIN_CODE_COOLDOWN_KEY + phone, LOGIN_CODE_IP_KEY + clientIp),
                String.valueOf(LOGIN_CODE_COOLDOWN_SEC),
                String.valueOf(LOGIN_CODE_IP_LIMIT),
                String.valueOf(LOGIN_CODE_IP_WINDOW_SEC)
        );
        int limit = limitResult == null ? -1 : limitResult.intValue();
        if (limit == 1) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }
        if (limit == 2) {
            return Result.fail("当前网络发送次数过多，请稍后再试");
        }
        if (limit != 0) {
            return Result.fail("验证码发送失败，请稍后重试");
        }

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

        //校检手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        };

        // 连续输错 5 次锁定 15 分钟，防止验证码爆破
        String failKey = LOGIN_FAIL_KEY + phone;
        String failCount = stringRedisTemplate.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount) >= LOGIN_FAIL_MAX) {
            Long ttl = stringRedisTemplate.getExpire(failKey, TimeUnit.SECONDS);
            long minutes = (ttl == null || ttl <= 0) ? 15 : (ttl + 59) / 60;
            return Result.fail("验证码错误次数过多，请 " + minutes + " 分钟后再试");
        }

        //String cacheCode = (String) session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        //校检验证码
        if(cacheCode == null || !cacheCode.equals(code)){
            // 记录一次失败，首次失败时设置锁定窗口
            Long count = stringRedisTemplate.opsForValue().increment(failKey);
            if (count != null && count == 1L) {
                stringRedisTemplate.expire(failKey, LOGIN_FAIL_LOCK_SEC, TimeUnit.SECONDS);
            }
            return Result.fail("验证码错误");
        }

        // 登录成功，清空失败计数
        stringRedisTemplate.delete(failKey);

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
