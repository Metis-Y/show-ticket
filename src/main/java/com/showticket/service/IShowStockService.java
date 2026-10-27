package com.showticket.service;

import com.showticket.entity.ShowStock;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 演出票档（库存 + 开售时间）服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IShowStockService extends IService<ShowStock> {

    /**
     * 把某场演出的库存和开售窗口预热到 Redis
     */
    void warmUp(Long showId);
}
