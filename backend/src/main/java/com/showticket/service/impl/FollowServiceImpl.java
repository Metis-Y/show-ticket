package com.showticket.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.showticket.dto.Result;
import com.showticket.dto.UserDTO;
import com.showticket.entity.Follow;
import com.showticket.mapper.FollowMapper;
import com.showticket.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.service.IUserService;
import com.showticket.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.showticket.utils.RedisConstants.FOLLOW_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //告诉前端有没有关注
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();// 3.判断
        return Result.ok(count > 0);
    }


    //点击是关注还是取关
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            // 3.1.关注：插入数据
            boolean success=save(new Follow().setUserId(userId).setFollowUserId(followUserId));
            if (success){
                stringRedisTemplate.opsForSet().add(FOLLOW_KEY + userId, followUserId.toString());
            }
        } else {
            // 3.2.取消关注：删除数据
            boolean success=remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if (success) {
                stringRedisTemplate.opsForSet().remove(FOLLOW_KEY + userId, followUserId.toString());
            }
        }

        return Result.ok();
    }

    //查询共同关注
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        //2.求交集
        String key2 = FOLLOW_KEY + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
        //无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(users);
    }


}
