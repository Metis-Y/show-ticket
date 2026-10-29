package com.showticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.showticket.dto.Result;
import com.showticket.dto.UserDTO;
import com.showticket.entity.TicketOrder;
import com.showticket.mapper.TicketOrderMapper;
import com.showticket.mq.TicketMessageProducer;
import com.showticket.mq.TicketOrderMessage;
import com.showticket.service.IShowStockService;
import com.showticket.service.ITicketOrderService;
import com.showticket.utils.RedisIdWorker;
import com.showticket.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.showticket.utils.RedisConstants.GRAB_STOCK_KEY;
import static com.showticket.utils.RedisConstants.KEY_PREFIX;
import static com.showticket.utils.RedisConstants.TICKET_ORDER_KEY;

/**
 * <p>
 *  抢票 / 订单服务
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
@Slf4j
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    /** 订单状态：1 未支付 */
    public static final int STATUS_UNPAID = 1;
    /** 订单状态：2 已支付 */
    public static final int STATUS_PAID = 2;
    /** 订单状态：4 已取消 */
    public static final int STATUS_CANCELED = 4;

    @Resource
    private IShowStockService showStockService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TicketMessageProducer ticketMessageProducer;

    private static final DefaultRedisScript<Long> GRAB_SCRIPT;

    static {
        GRAB_SCRIPT = new DefaultRedisScript<>();
        GRAB_SCRIPT.setLocation(new ClassPathResource("grab.lua"));
        GRAB_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result grabTicket(Long showId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        Long orderId = redisIdWorker.nextId("order");

        // 1. Redis 内原子校验：开售时间 + 库存 + 一人一票
        int code = executeGrabScript(showId, userId);
        if (code == 5) {
            // Redis 没有票务数据（被清空/未预热），从 DB 懒加载后重试一次
            showStockService.warmUp(showId);
            code = executeGrabScript(showId, userId);
        }
        if (code != 0) {
            return Result.fail(grabFailMessage(code));
        }

        // 2. 发送异步下单消息，抢票接口只做 Redis 操作，保持轻量
        try {
            ticketMessageProducer.sendGrabOrder(new TicketOrderMessage(orderId, userId, showId));
            // 3. 投递延迟关单消息，TTL 到期后由死信路由到超时队列
            ticketMessageProducer.sendOrderTimeout(orderId);
        } catch (Exception e) {
            log.error("[抢票] 消息发送失败，回补 Redis 预扣库存 showId={}, userId={}", showId, userId, e);
            rollbackSeat(showId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        log.info("[抢票] 预扣库存成功，等待异步落库 orderId={}, userId={}, showId={}", orderId, userId, showId);
        // 订单号 18 位，转字符串返回，避免前端 JS 精度丢失后拿错误 ID 去支付/查询
        return Result.ok(orderId.toString());
    }

    private int executeGrabScript(Long showId, Long userId) {
        Long result = stringRedisTemplate.execute(
                GRAB_SCRIPT,
                Collections.emptyList(),
                KEY_PREFIX,
                showId.toString(),
                userId.toString(),
                String.valueOf(Instant.now().getEpochSecond())
        );
        return result.intValue();
    }

    //状态机
    private String grabFailMessage(int code) {
        return switch (code) {
            case 1 -> "票已售罄";
            case 2 -> "每人限购一张，请勿重复下单";
            case 3 -> "还未开票，请稍后再来";
            case 4 -> "本场演出已停止售票";
            case 5 -> "演出不存在或暂未开票";
            default -> "抢票失败，请稍后重试";
        };
    }

    /**
     * 消费抢票消息：落库。
     * 幂等：同一订单号已存在直接返回；扣库存与插单在同一事务内，任一失败都不留脏数据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTicketOrder(TicketOrderMessage message) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long showId = message.getShowId();

        // 1. 幂等：同一条消息重复投递直接忽略
        if (getById(orderId) != null) {
            log.warn("[下单] 消息重复消费，订单已存在，直接忽略 orderId={}", orderId);
            return;
        }

        // 2. 扣减 DB 库存：Redis 只是预扣，DB 才是最终依据
        boolean deducted = showStockService.update()
                .setSql("stock = stock - 1")
                .eq("show_id", showId)
                .gt("stock", 0)
                .update();
        if (!deducted) {
            // Redis 有票而 DB 无票，说明数据不一致：回补 Redis 并告警，正常 ack 避免无意义重试
            log.error("[下单] DB 库存不足，回补 Redis 占位 orderId={}, showId={}", orderId, showId);
            rollbackSeat(showId, userId);
            return;
        }

        // 3. 订单落库
        TicketOrder order = new TicketOrder()
                .setId(orderId)
                .setUserId(userId)
                .setShowId(showId)
                .setStatus(STATUS_UNPAID)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        save(order);
        log.info("[下单] 订单落库成功 orderId={}, userId={}, showId={}", orderId, userId, showId);
    }


    //支付按钮功能
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() == STATUS_PAID) {
            return Result.ok("订单已支付");
        }
        if (order.getStatus() != STATUS_UNPAID) {
            return Result.fail("订单已关闭，无法支付");
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：只有未支付才能改成已支付，防止与超时关单并发
        boolean updated = update()
                .set("status", STATUS_PAID)
                .set("pay_time", now)
                .set("update_time", now)
                .eq("id", orderId)
                .eq("status", STATUS_UNPAID)
                .update();
        if (!updated) {
            return Result.fail("订单状态已变更，请刷新后重试");
        }
        return Result.ok();
    }

    /**
     * 超时关单：延迟消息到期后执行。
     * 幂等：只有 status=1 才会被更新，重复执行/支付后执行都不会重复回补库存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTimeoutOrder(Long orderId) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            // 下单消息还没消费完，抛异常让 MQ 稍后重试
            throw new IllegalStateException("订单尚未创建，稍后重试 orderId=" + orderId);
        }
        if (order.getStatus() != STATUS_UNPAID) {
            log.info("[关单] 订单状态为 {}，无需取消 orderId={}", order.getStatus(), orderId);
            return true;
        }

        boolean updated = update()
                .set("status", STATUS_CANCELED)
                .set("update_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", STATUS_UNPAID)
                .update();
        if (!updated) {
            log.info("[关单] 订单已被并发处理，跳过 orderId={}", orderId);
            return true;
        }

        // 回补 DB 库存 + Redis 库存，并释放一人一票占位
        showStockService.update()
                .setSql("stock = stock + 1")
                .eq("show_id", order.getShowId())
                .update();
        rollbackSeat(order.getShowId(), order.getUserId());
        log.info("[关单] 超时未支付，已取消并回补库存 orderId={}, showId={}", orderId, order.getShowId());
        return true;
    }

    //查询详情
    @Override
    public Result queryOrderById(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            return Result.fail("订单不存在");
        }
        return Result.ok(order);
    }

    @Override
    public Result queryMyOrders() {
        Long userId = UserHolder.getUser().getId();
        List<TicketOrder> orders = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .list();
        return Result.ok(orders);
    }

    /**
     * 回补 Redis：票数 +1，并允许该用户重新抢票
     */
    private void rollbackSeat(Long showId, Long userId) {
        stringRedisTemplate.opsForValue().increment(GRAB_STOCK_KEY + showId);
        stringRedisTemplate.opsForSet().remove(TICKET_ORDER_KEY + showId, userId.toString());
    }
}
