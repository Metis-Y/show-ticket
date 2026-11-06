package com.showticket.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.showticket.dto.Result;
import com.showticket.entity.Venue;
import com.showticket.entity.ShowType;
import com.showticket.mapper.ShowTypeMapper;
import com.showticket.service.IShowTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.util.List;

import static com.showticket.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.showticket.utils.RedisConstants.LOCK_VENUE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
public class ShowTypeServiceImpl extends ServiceImpl<ShowTypeMapper, ShowType> implements IShowTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShowType() {
        //1先查redis2没有再查数据库，有就返回3查完数据库放到reids和返回
        String key = CACHE_VENUE_KEY;
        String showTypeJson=stringRedisTemplate.opsForValue().get(key);
        //存在直接返回
        if (StrUtil.isNotBlank(showTypeJson)){
            return Result.ok(JSONUtil.toList(showTypeJson, ShowType.class));
        }
        /*用list数据结构的写法,存入redis的是json格式
        List<String> jsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (jsonList != null && !jsonList.isEmpty()) {
            List<ShowType> typeList = new ArrayList<>();
            for (String json : jsonList) {
                typeList.add(JSONUtil.toBean(json, ShowType.class));
            }
            return Result.ok(typeList);
        }*/

        //不存在，查询数据库
        List<ShowType> showTypes = query().orderByAsc("sort").list();
        if (showTypes.isEmpty()) {
            return Result.fail("没有找到场馆");
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(showTypes));
        return Result.ok(showTypes);
    }
}
