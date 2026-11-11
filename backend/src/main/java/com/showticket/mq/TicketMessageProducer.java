package com.showticket.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import static com.showticket.utils.RedisConstants.GRAB_STOCK_KEY;
import static com.showticket.utils.RedisConstants.TICKET_ORDER_KEY;

/**
 * 抢票相关消息的统一出口。
 *
 * 抢票下单消息开启 publisher confirm + return：把「发出去默认成功」变成能感知
 * broker 是否确认收下 / 是否路由成功；nack 或退回时回补 Redis 预扣（库存 + 限购占位），
 * 否则会「扣了票却没订单」。
 * 延迟关单消息不确认：它只是「超时后自动关单」的定时器，丢了由定时扫表兜底，
 * 不需要和抢票消息同等对待。
 */
@Component
@Slf4j
public class TicketMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 抢票下单消息的投递凭证。confirm 回调里要靠它拿到 showId/userId 来回补——
     * 此刻订单还没落库，只有 Redis 里的预扣记录，没有订单可查。
     */
    private static class TicketCorrelationData extends CorrelationData {
        private final Long showId;
        private final Long userId;

        TicketCorrelationData(TicketOrderMessage m) {
            super(String.valueOf(m.getOrderId()));
            this.showId = m.getShowId();
            this.userId = m.getUserId();
        }
    }

    @PostConstruct
    public void initPublisherConfirm() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            // 只有抢票下单消息带了凭证；延迟关单消息没带，直接忽略
            if (!(correlationData instanceof TicketCorrelationData cd)) {
                return;
            }
            // broker 没确认收到（连接断 / exchange 不存在 / 被拒）
            if (!ack) {
                log.error("[MQ] 抢票消息 broker 未确认，回补 showId={}, userId={}，cause={}",
                        cd.showId, cd.userId, cause);
                compensate(cd.showId, cd.userId);
                return;
            }
            // broker 收到了但没匹配到任何队列：Spring AMQP 把被退回的消息挂到 getReturned()
            if (cd.getReturned() != null) {
                log.error("[MQ] 抢票消息不可路由被退回，回补 showId={}, userId={}",
                        cd.showId, cd.userId);
                compensate(cd.showId, cd.userId);
            }
        });
    }

    /**
     * 抢票成功后异步下单
     */
    public void sendGrabOrder(TicketOrderMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TICKET_EXCHANGE, RabbitMqConfig.GRAB_ROUTING_KEY, message,
                new TicketCorrelationData(message));
        log.debug("[MQ] 抢票消息已发送 orderId={}", message.getOrderId());
    }

    /**
     * 投递延迟消息，TTL 到期后转投超时队列
     */
    public void sendOrderTimeout(Long orderId) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TICKET_EXCHANGE, RabbitMqConfig.DELAY_ROUTING_KEY,
                new OrderTimeoutMessage(orderId));
        log.debug("[MQ] 延迟关单消息已发送 orderId={}", orderId);
    }

    /**
     * 回补 Redis 预扣：库存 +1、移出限购占位。
     * 与 TicketOrderServiceImpl#rollbackSeat 语义一致，只有 Redis 动作——消息没进 MQ 时
     * 订单尚未落库、DB 库存也没扣，所以不需要动 DB。
     */
    private void compensate(Long showId, Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(GRAB_STOCK_KEY + showId);
            stringRedisTemplate.opsForSet().remove(TICKET_ORDER_KEY + showId, userId.toString());
        } catch (Exception e) {
            log.error("[MQ] 回补 Redis 失败 showId={}, userId={}", showId, userId, e);
        }
    }
}
