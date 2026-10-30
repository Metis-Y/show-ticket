package com.showticket.service.impl;

import com.showticket.entity.ShowStock;
import com.showticket.mapper.ShowStockMapper;
import com.showticket.service.IShowStockService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static com.showticket.utils.RedisConstants.GRAB_STOCK_KEY;
import static com.showticket.utils.RedisConstants.SHOW_WINDOW_KEY;

/**
 * <p>
 * 演出票档（库存 + 开售时间）服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
@Slf4j
public class ShowStockServiceImpl extends ServiceImpl<ShowStockMapper, ShowStock> implements IShowStockService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 预热：把库存和开售窗口写进 Redis，抢票链路只走 Redis 不走 DB。
     *
     * key 拼法必须和 grab.lua 里保持一致（前缀 + ticket:stock/show/window + showId）；
     * 开售时间用 hash 存 epoch 秒，交给 Lua 比较，避免 Java 与 Redis 两边的时区、网络往返问题。
     */
    @Override
    public void warmUp(Long showId) {
        ShowStock stock = getById(showId);
        if (stock == null) {
            log.warn("[预热] 票档不存在 showId={}", showId);
            return;
        }
        stringRedisTemplate.opsForValue()
                .set(GRAB_STOCK_KEY + showId, String.valueOf(stock.getStock()));

        Map<String, String> window = new HashMap<>(4);
        if (stock.getBeginTime() != null) {
            window.put("begin", String.valueOf(stock.getBeginTime().atZone(ZoneId.systemDefault()).toEpochSecond()));
        }
        if (stock.getEndTime() != null) {
            window.put("end", String.valueOf(stock.getEndTime().atZone(ZoneId.systemDefault()).toEpochSecond()));
        }
        if (!window.isEmpty()) {
            stringRedisTemplate.opsForHash().putAll(SHOW_WINDOW_KEY + showId, window);
        }
        //log.info("[预热] showId={} 库存={} 开售窗口={}", showId, stock.getStock(), window);
    }
}
