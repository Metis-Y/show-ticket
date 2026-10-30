package com.showticket.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.showticket.dto.Result;
import com.showticket.dto.UserDTO;
import com.showticket.entity.Review;
import com.showticket.entity.User;
import com.showticket.service.IReviewService;
import com.showticket.service.IUserService;
import com.showticket.utils.SystemConstants;
import com.showticket.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

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
@RequestMapping("/review")
public class ReviewController {

    @Resource
    private IReviewService reviewService;

    @PostMapping
    public Result saveReview(@RequestBody Review review) {
        return reviewService.saveReview(review);
    }

    @PutMapping("/like/{id}")
    public Result likeReview(@PathVariable("id") Long id) {
        return reviewService.likeReview(id);
    }

    @GetMapping("/of/me")
    public Result queryMyReview(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Review> page = reviewService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Review> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotReview(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return reviewService.queryHotReview(current);
    }

    @GetMapping("/{id}")
    public Result queryReviewById (@PathVariable("id") Long id){
        return reviewService.queryReviewById(id);
    }

    @GetMapping("/likes/{id}")
    public Result queryReviewLikes(@PathVariable("id") Long id) {
        return reviewService.queryReviewLikes(id);
    }

    @GetMapping("/of/user")
    public Result queryReviewByUserId(@RequestParam(value = "current", defaultValue = "1")
                                        Integer current,@RequestParam("id") Long id) {
        //根据用户查询
        Page<Review> page = reviewService.query()
                .eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));//获取当前页数据
        List<Review> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/of/follow")
    public Result queryReviewOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return reviewService.queryReviewOfFollow(max, offset);

    }
}
