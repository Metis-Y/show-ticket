package com.showticket.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.showticket.dto.Result;
import com.showticket.entity.Venue;
import com.showticket.mapper.VenueMapper;
import com.showticket.service.IVenueService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.utils.CacheClient;
import com.showticket.utils.RedisConstants;
import com.showticket.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.showticket.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //方便递归调用
        //Venue venue = queryWithMutex(id);
        //调用逻辑过期加线程池加互斥锁
        /*暂时不管 缓存 不存在的问题*/
        //在测试类那里预热
        //嫌麻烦就换回去无锁的方法
        Venue venue = cacheClient.queryWithLogicalExpire(
                RedisConstants.CACHE_VENUE_KEY, id, Venue.class,
                this::getById, RedisConstants.CACHE_VENUE_TTL, TimeUnit.MINUTES
        );

        if (venue == null) {
            return Result.fail("场馆不存在");
        }
        return Result.ok(venue);
    }

    /*互斥锁解决缓存穿透*/
    /*public Venue queryWithMutex(Long id) {
        String key = CACHE_VENUE_KEY + id;
        String venueJson = stringRedisTemplate.opsForValue().get(key);
        //存在直接返回
        if (StrUtil.isNotBlank(venueJson)) {
            return JSONUtil.toBean(venueJson, Venue.class);
        }
        //缓存没有，返回null
        //这里确定venueJson是否为数据库没有该数据时设置的""
        if (venueJson != null) {
            return null;
        }

        //不存在，查询数据库
        Venue venue;
        //添加锁
        String lockKey = LOCK_VENUE_KEY + id;
        try {
            if (!tryLock(lockKey)) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            venueJson = stringRedisTemplate.opsForValue().get(key);
            //存在直接返回
            if (StrUtil.isNotBlank(venueJson)) {
                return JSONUtil.toBean(venueJson, Venue.class);
            }
            //缓存没有，返回null
            //这里确定venueJson是否为数据库没有该数据时设置的""
            if (venueJson != null) {
                return null;
            }

            venue = getById(id);
            //模拟延迟
            Thread.sleep(200);
            if (venue == null) {
                //缓存没有，数据库也没有，设置为""
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //.opsForValue()不能写对象，要转换为json格式
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(venue), CACHE_VENUE_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return venue;

    }*/

    /*逻辑过期解决缓存穿透*/
    /*public Venue queryWithLogicalExpire(Long id) {
        String key = CACHE_VENUE_KEY + id;
        String venueJson = stringRedisTemplate.opsForValue().get(key);
        //不存在返回null
        if (StrUtil.isBlank(venueJson)) {
            return null;
        }

        //存在，查看时间有没有过期
        RedisData redisData = JSONUtil.toBean(venueJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        //序列化
        Venue venue = JSONUtil.toBean(data, Venue.class);
        //没过期，返回数据
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return venue;
        }

        //过期
        String lockKey = LOCK_VENUE_KEY + id;

        //获得锁
        if (tryLock(lockKey)) {
            //获得锁再判断一次
            venueJson = stringRedisTemplate.opsForValue().get(key);
            //存在直接返回
            if (StrUtil.isNotBlank(venueJson)) {
                return JSONUtil.toBean(venueJson, Venue.class);
            }
            //缓存没有，返回null
            //这里确定venueJson是否为数据库没有该数据时设置的""
            if (venueJson != null) {
                return null;
            }
            //实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveVenue2Redis(id, 20L);
                }catch (Exception e) {
                    throw new RuntimeException(e);
                }finally{
                    unlock(lockKey);
                }
            });
        }
        //没获得锁,返回旧数据
        return venue;

    }*/


    //利用Redis实现互斥锁，防止缓存击穿
    /*private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    //设置逻辑过期时间
    private void saveVenue2Redis(Long id, Long expireSeconds) {
        Venue venue = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(venue);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));// 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_VENUE_KEY + id, JSONUtil.toJsonStr(redisData));

    }*/
    //缓存重建线程池
/*
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
*/

    @Override
    @Transactional
    public Result updateVenue(Venue venue) {
        if (venue.getId() == null) {
            return Result.fail("场馆id不能为空");
        }
        //先更新数据库，然后删除redis缓存--------查询的时候再加缓存
        updateById(venue);
        stringRedisTemplate.delete(CACHE_VENUE_KEY + venue.getId());

        return Result.ok();
    }

    @Override
    public Result queryVenueByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null | y == null) {
            //不需要坐标查询，按数据库查询
            Page<Venue> page = query().eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));// 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis、按照距离排序、分页。结果：venueId、distance
        String key = VENUE_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y), new Distance( 5000),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4解析出id
        if (results == null) {
            return Result.ok();
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list= results.getContent();
        //防止没有数据了还在继续往下查导致的报错
        if (list.size() <= from) {
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取场馆id
            String venueIdStr = result.getContent().getName();
            ids.add(Long.valueOf(venueIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(venueIdStr, distance);
        });
        // 5.根据id查询Venue
        String idStr = StrUtil.join( ",", ids);
        List<Venue> venues = query().in("id", ids).last( "oRDER BY FIELD(id," + idStr + ")").list();
        for (Venue venue : venues) {
            venue.setDistance(distanceMap.get(venue.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(venues);
    }

    /**
     * UV 统计：HyperLogLog 只记录基数估算值，单个 key 固定占用约 12KB，
     * 相比 Set 存全量用户 id 能省几个数量级的内存。
     */
    @Override
    public Result recordUvAndCount(Long venueId, String visitor) {
        String key = VENUE_UV_KEY + venueId;
        stringRedisTemplate.opsForHyperLogLog().add(key, visitor);
        Long uv = stringRedisTemplate.opsForHyperLogLog().size(key);
        return Result.ok(uv);
    }
}
