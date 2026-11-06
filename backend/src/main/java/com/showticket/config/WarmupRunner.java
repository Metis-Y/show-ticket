package com.showticket.config;

import com.showticket.entity.ShowStock;
import com.showticket.entity.Venue;
import com.showticket.service.IShowStockService;
import com.showticket.service.IVenueService;
import com.showticket.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.showticket.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.showticket.utils.RedisConstants.CACHE_VENUE_TTL;
import static com.showticket.utils.RedisConstants.VENUE_GEO_KEY;

/**
 * 启动预热：
 * 1. 场馆按分类写入 GEO，支撑「附近场馆」查询
 * 2. 场馆详情写入逻辑过期缓存，避免首次访问缓存穿透
 * 3. 票档库存 + 开售窗口写入 Redis，支撑抢票 Lua 校验
 */
@Component
@ConditionalOnProperty(prefix = "app.warmup", name = "enabled", havingValue = "true")
@Slf4j
public class WarmupRunner implements ApplicationRunner {

    @Resource
    private IVenueService venueService;
    @Resource
    private IShowStockService showStockService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Venue> venues = venueService.list();
            warmUpGeo(venues);
            warmUpVenueCache(venues);

            List<ShowStock> stocks = showStockService.list();
            stocks.forEach(stock -> showStockService.warmUp(stock.getShowId()));

            log.info("[预热] 完成：场馆 {} 个，票档 {} 个", venues.size(), stocks.size());
        } catch (Exception e) {
            log.warn("[预热] 失败，请检查 MySQL / Redis 是否可用：{}", e.getMessage());
        }
    }

    private void warmUpGeo(List<Venue> venues) {
        Map<Long, List<Venue>> grouped = venues.stream()
                .filter(v -> v.getX() != null && v.getY() != null)
                .collect(Collectors.groupingBy(Venue::getTypeId));

        for (Map.Entry<Long, List<Venue>> entry : grouped.entrySet()) {
            String key = VENUE_GEO_KEY + entry.getKey();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(entry.getValue().size());
            for (Venue venue : entry.getValue()) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        venue.getId().toString(),
                        new Point(venue.getX(), venue.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
        log.info("[预热] 场馆 GEO 写入完成，共 {} 个分类", grouped.size());
    }

    private void warmUpVenueCache(List<Venue> venues) {
        for (Venue venue : venues) {
            cacheClient.setWithLogicalExpire(
                    CACHE_VENUE_KEY + venue.getId(), venue, CACHE_VENUE_TTL, TimeUnit.MINUTES);
        }
    }
}
