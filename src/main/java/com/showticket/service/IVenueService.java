package com.showticket.service;

import com.showticket.dto.Result;
import com.showticket.entity.Venue;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IVenueService extends IService<Venue> {

    Result queryById(Long id);

    Result updateVenue(Venue venue);

    Result queryVenueByType(Integer typeId, Integer current, Double x, Double y);

    /**
     * 记录一次场馆详情页访问，并返回 UV（HyperLogLog 估算）
     */
    Result recordUvAndCount(Long venueId, String visitor);
}
