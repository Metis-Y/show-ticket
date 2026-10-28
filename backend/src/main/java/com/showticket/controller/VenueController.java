package com.showticket.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.showticket.dto.Result;
import com.showticket.dto.UserDTO;
import com.showticket.entity.Venue;
import com.showticket.service.IVenueService;
import com.showticket.utils.SystemConstants;
import com.showticket.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@RestController
@RequestMapping("/venue")
public class VenueController {

    @Resource
    public IVenueService venueService;

    /**
     * 根据id查询场馆信息
     * @param id 场馆id
     * @return 场馆详情数据
     */
    @GetMapping("/{id}")
    public Result queryVenueById(@PathVariable("id") Long id) {
        return venueService.queryById(id);
    }

    /**
     * 新增场馆信息
     * @param venue 场馆数据
     * @return 场馆id
     */
    @PostMapping
    public Result saveVenue(@RequestBody Venue venue) {
        // 写入数据库
        venueService.save(venue);
        // 返回场馆id
        return Result.ok(venue.getId());
    }

    /**
     * 更新场馆信息
     * @param venue 场馆数据
     * @return 无
     */
    @PutMapping
    public Result updateVenue(@RequestBody Venue venue) {
        // 写入数据库
        //venueService.updateById(venue);
        //return Result.ok();
        return venueService.updateVenue(venue);
    }

    /**
     * 根据场馆类型分页查询场馆信息
     * @param typeId 场馆类型
     * @param current 页码
     * @return 场馆列表
     */
    @GetMapping("/of/type")
    public Result queryVenueByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {

        return venueService.queryVenueByType(typeId, current, x, y);
    }

    /**
     * 场馆详情页访问计数，返回 UV（HyperLogLog，去重估算，内存固定约 12KB）
     */
    @GetMapping("/uv/{id}")
    public Result venueUv(@PathVariable("id") Long id, HttpServletRequest request) {
        UserDTO user = UserHolder.getUser();
        String visitor = user != null ? "u:" + user.getId() : "ip:" + request.getRemoteAddr();
        return venueService.recordUvAndCount(id, visitor);
    }

    /**
     * 根据场馆名称关键字分页查询场馆信息
     * @param name 场馆名称关键字
     * @param current 页码
     * @return 场馆列表
     */
    @GetMapping("/of/name")
    public Result queryVenueByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Venue> page = venueService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
