package com.showticket.controller;


import com.showticket.dto.Result;
import com.showticket.entity.Show;
import com.showticket.service.IShowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@RestController
@RequestMapping("/show")
public class ShowController {

    @Resource
    private IShowService showService;

    /**
     * 新增普通券
     * @param show 演出信息
     * @return 演出id
     */
    @PostMapping
    public Result addShow(@RequestBody Show show) {
        showService.save(show);
        return Result.ok(show.getId());
    }

    /**
     * 新增抢票券
     * @param show 演出信息，包含抢票信息
     * @return 演出id
     */
    @PostMapping("grab")
    public Result addGrabShow(@RequestBody Show show) {
        showService.addGrabShow(show);
        return Result.ok(show.getId());
    }

    /**
     * 查询场馆的演出列表
     * @param venueId 场馆id
     * @return 演出列表
     */
    @GetMapping("/list/{venueId}")
    public Result queryShowOfVenue(@PathVariable("venueId") Long venueId) {
       return showService.queryShowOfVenue(venueId);
    }
}
