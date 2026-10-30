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

import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.showticket.utils.RedisConstants.GRAB_STOCK_KEY;
import static com.showticket.utils.RedisConstants.KEY_PREFIX;
import static com.showticket.utils.RedisConstants.TICKET_ORDER_KEY;

/**
 * 抢票 / 订单服务
 *
 * 抢票链路分两段：
 * 1. 接口层只做 Redis 校验与预扣（grab.lua），立即返回订单号；
 * 2. 扣 DB 库存、订单落库交给 MQ 消费者异步完成。
 * 超时未支付由延迟消息触发关单并回补库存。
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@Service
@Slf4j
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    // 订单状态，与 ticket_order.status 字段一一对应
    /** 未支付 */
    public static final int STATUS_UNPAID = 1;
    /** 已支付 */
    public static final int STATUS_PAID = 2;
    /** 已取消 */
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

    //抢票功能
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
            // 3. 投递延迟关单消息，TTL 到期后转投超时队列
            ticketMessageProducer.sendOrderTimeout(orderId);
        } catch (Exception e) {
            // 消息没发出去就不会有消费者扣 DB 库存，Redis 预扣必须回滚，否则票会凭空少一张
            log.error("[抢票] 消息发送失败，回补 Redis 预扣库存 showId={}, userId={}", showId, userId, e);
            rollbackSeat(showId, userId);
            return Result.fail("系统繁忙，请稍后重试");
        }

        log.info("[抢票] 预扣库存成功，等待异步落库 orderId={}, userId={}, showId={}", orderId, userId, showId);
        // 订单号 18 位，转字符串返回，避免前端 JS 精度丢失后拿错误 ID 去支付/查询
        return Result.ok(orderId.toString());
    }

    /**
     * 执行 grab.lua：KEYS 传空，脚本内自行用前缀 + showId 拼出所有 key
     *
     * @return 0 校验通过且已预扣；1 售罄；2 重复下单；3 未开票；4 停售；5 未预热
     */
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

    /** 失败码 -> 提示文案，与 grab.lua 的 return 值一一对应 */
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
    //校验通过，抢票下单消费者调用这个方法，开始创建订单
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
        //    条件更新 stock > 0 起到乐观锁作用，更新 0 行即说明 DB 已无票
        boolean deducted = showStockService.update()
                .setSql("stock = stock - 1")
                .eq("show_id", showId)
                .gt("stock", 0)
                .update();
        if (!deducted) {
            // Redis 有票而 DB 无票，说明数据不一致：回补 Redis 并告警
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


    /**
     * 支付：只有未支付订单能改成已支付。
     * 用条件更新（where status = 1）代替先查后改，防止与超时关单并发时状态被覆盖。
     */
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
    //超时关单，延迟消息到期后超时消费者执行
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTimeoutOrder(Long orderId) {
        TicketOrder order = getById(orderId);
        if (order == null) {
            // 下单消息还没消费完，抛出异常让事务回滚、消息重回队列重试；
            // 重试耗尽后会进死信队列，不会丢单
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

    /** 查订单详情，只允许查自己的订单，防止遍历订单号 */
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
     * 回补 Redis：票数 +1，并把 userId 移出一人一票集合。
     * 不删集合里的 userId 的话，该用户这一场将永远抢不了票。
     * 注意只回补 Redis，DB 库存由调用方按场景自己处理（下单失败时 DB 未扣、关单时 DB 已扣需加回）。
     */
    private void rollbackSeat(Long showId, Long userId) {
        stringRedisTemplate.opsForValue().increment(GRAB_STOCK_KEY + showId);
        stringRedisTemplate.opsForSet().remove(TICKET_ORDER_KEY + showId, userId.toString());
    }
}
