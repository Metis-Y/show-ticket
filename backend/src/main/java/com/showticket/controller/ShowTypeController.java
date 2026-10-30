package com.showticket.controller;


import com.showticket.dto.Result;
import com.showticket.entity.ShowType;
import com.showticket.service.IShowTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@RestController
@RequestMapping("/show-type")
public class ShowTypeController {
    @Resource
    private IShowTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        //List<ShowType> typeList = typeService
        //        .query().orderByAsc("sort").list();
        //return Result.ok(typeList);
        return typeService.queryShowType();
    }
}
