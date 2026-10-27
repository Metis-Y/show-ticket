package com.showticket.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.showticket.dto.Result;
import com.showticket.dto.ScrollResult;
import com.showticket.dto.UserDTO;
import com.showticket.entity.Review;
import com.showticket.entity.Follow;
import com.showticket.entity.User;
import com.showticket.mapper.ReviewMapper;
import com.showticket.service.IReviewService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.service.IFollowService;
import com.showticket.service.IUserService;
import com.showticket.utils.SystemConstants;
import com.showticket.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.showticket.utils.RedisConstants.REVIEW_LIKED_KEY;
import static com.showticket.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
public class ReviewServiceImpl extends ServiceImpl<ReviewMapper, Review> implements IReviewService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    //查询所有review
    @Override
    public Result queryHotReview(Integer current) {
        // 根据用户查询
        Page<Review> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Review> records = page.getRecords();
        // 查询用户和是否点赞
        records.forEach(review -> {
            queryReviewUser(review);
            //是否点赞没存数据库，要单独set
            isReviewLike(review);
        });
        return Result.ok(records);
    }

    //查询review用户信息
    private void queryReviewUser(Review review) {
        Long userId = review.getUserId();
        User user = userService.getById(userId);
        review.setName(user.getNickName());
        review.setIcon(user.getIcon());
    }

    //根据id查询review
    @Override
    public Result queryReviewById(Long id) {
        // 1.查询review
        Review review = getById(id);
        if (review == null) {
            return Result.fail("剧评不存在!");
        }
        // 2.查询review有关的用户
        queryReviewUser(review);
        //判断当前用户是否已点赞,只存在redis，不存数据库，所以要单独调用set方法
        isReviewLike(review);
        return Result.ok(review);
    }

    //根据id得到时间戳来判断用户是否点赞
    private void isReviewLike(Review review) {

        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            //用户未登录，无需查询是否点赞
            return;
        }

        Long userId = user.getId();
        String key = REVIEW_LIKED_KEY + review.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        review.setIsLike(score != null);
    }

    @Override
    public Result likeReview(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前登录用户是否已经点赞，返回时间戳判断点赞时间，方便排行
        String key = REVIEW_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();// 3.2.保存用户到Redis的set集合
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }
        else {
            //4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();//4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }

        }
        return Result.ok();

    }

    //根据reviewid查询点赞用户，按时间顺序排行，取前五
    @Override
    public Result queryReviewLikes(Long id) {
        String key = REVIEW_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());

        }
        String idStr = String.join(",", top5);
        List<Long> likeUserIds = top5.stream().map(Long::valueOf).toList();
        //指定按时间顺序排序，sql默认按id排序
        List<UserDTO> userDTOs = userService.query().in("id", likeUserIds)
                .last("ORDER BY FIELD(id," + idStr + ")").list().stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class)).toList();

        return Result.ok(userDTOs);
    }

    @Override
    public Result saveReview(Review review) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        review.setUserId(user.getId());
        // 保存观演博文
        boolean isSuccess=save(review);
        if(!isSuccess) {
            return Result.fail("新增剧评失败!");
        }
        // 3.查询剧评作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();// 4.推送剧评id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, review.getId().toString(), System.currentTimeMillis());

        }

        // 返回id
        return Result.ok(review.getId());
    }

    //用滑动窗口实现分页查询关注的review
    @Override
    public Result queryReviewOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key,  0, max, offset,  2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4.解析数据：reviewId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳)
            minTime = tuple.getScore().longValue();
        }

        // 5.根据id查询review
        String idStr = StrUtil.join( ",", ids);
        List<Review> reviews = query().in("id", ids).last(  "ORDER BY FIELD(id," + idStr + ")").list();
        for (Review review : reviews) {
            // 5.1.查询review有关的用户
            queryReviewUser(review);
            //5.2.查询review是否被点赞
            isReviewLike(review);
        }

        //6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(reviews);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);

    }


}
