package com.showticket.service;

import com.showticket.dto.Result;
import com.showticket.entity.Review;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface IReviewService extends IService<Review> {

    Result queryHotReview(Integer current);

    Result queryReviewById(Long id);

    Result likeReview(Long id);

    Result queryReviewLikes(Long id);

    Result saveReview(Review review);

    Result queryReviewOfFollow(Long max, Integer offset);
}
