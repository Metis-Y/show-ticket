package com.showticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.showticket.entity.Show;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface ShowMapper extends BaseMapper<Show> {

    List<Show> queryShowOfVenue(@Param("venueId") Long venueId);
}
