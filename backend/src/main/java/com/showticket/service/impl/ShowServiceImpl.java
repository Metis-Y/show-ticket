package com.showticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.dto.Result;
import com.showticket.entity.Show;
import com.showticket.entity.ShowStock;
import com.showticket.mapper.ShowMapper;
import com.showticket.service.IShowService;
import com.showticket.service.IShowStockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
public class ShowServiceImpl extends ServiceImpl<ShowMapper, Show> implements IShowService {

    @Resource
    private IShowStockService showStockService;

    @Override
    public Result queryShowOfVenue(Long venueId) {
        // 查询场馆在售的演出（含票档库存与开售时间）
        List<Show> shows = getBaseMapper().queryShowOfVenue(venueId);
        return Result.ok(shows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addGrabShow(Show show) {
        // 1. 保存演出
        save(show);
        // 2. 保存票档（库存 + 开售时间）
        ShowStock showStock = new ShowStock()
                .setShowId(show.getId())
                .setStock(show.getStock())
                .setBeginTime(show.getBeginTime())
                .setEndTime(show.getEndTime());
        showStockService.save(showStock);
        // 3. 库存 + 开售窗口写入 Redis
        showStockService.warmUp(show.getId());
    }
}
