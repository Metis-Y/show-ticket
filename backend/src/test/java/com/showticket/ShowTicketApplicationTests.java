package com.showticket;

import com.showticket.entity.ShowStock;
import com.showticket.entity.Venue;
import com.showticket.service.IShowStockService;
import com.showticket.service.impl.VenueServiceImpl;
import com.showticket.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.showticket.utils.RedisConstants.CACHE_VENUE_KEY;
import static com.showticket.utils.RedisConstants.VENUE_GEO_KEY;

/**
 * 注意：这些测试会真实读写 MySQL 和 Redis。
 * 正常启动应用时 WarmupRunner 已经自动完成预热，这里保留用于手动重建 Redis 数据。
 */
@SpringBootTest
class ShowTicketApplicationTests {

    @Resource
    private CacheClient cacheClient;
    @Resource
    private VenueServiceImpl venueService;
    @Resource
    private IShowStockService showStockService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void warmUpVenueCache() {
        for (Venue venue : venueService.list()) {
            cacheClient.setWithLogicalExpire(
                    CACHE_VENUE_KEY + venue.getId(), venue, 10L, TimeUnit.MINUTES);
        }
    }

    @Test
    void warmUpTicketStock() {
        List<ShowStock> stocks = showStockService.list();
        stocks.forEach(stock -> showStockService.warmUp(stock.getShowId()));
    }

    @Test
    void loadVenueGeo() {
        List<Venue> venues = venueService.list();
        Map<Long, List<Venue>> grouped = venues.stream()
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
    }
}
