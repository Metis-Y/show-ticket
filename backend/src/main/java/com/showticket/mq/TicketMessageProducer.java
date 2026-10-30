package com.showticket.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 抢票相关消息的统一出口
 */
@Component
@Slf4j
public class TicketMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 抢票成功后异步下单
     */
    public void sendGrabOrder(TicketOrderMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TICKET_EXCHANGE, RabbitMqConfig.GRAB_ROUTING_KEY, message);
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
}
