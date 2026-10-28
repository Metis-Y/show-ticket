package com.showticket.service;

import com.showticket.dto.Result;
import com.showticket.entity.Show;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IShowService extends IService<Show> {

    Result queryShowOfVenue(Long venueId);

    void addGrabShow(Show show);
}
