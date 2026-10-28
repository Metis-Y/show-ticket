package com.showticket.service;

import com.showticket.dto.Result;
import com.showticket.entity.ShowType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IShowTypeService extends IService<ShowType> {

    Result queryShowType();
}
