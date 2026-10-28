package com.showticket.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 抢票相关消息的统一出口
 */
@Component
@Slf4j
public class TicketMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 抢票成功后的异步下单消息
     */
    public void sendGrabOrder(TicketOrderMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TICKET_EXCHANGE, RabbitMqConfig.GRAB_ROUTING_KEY, message);
        log.debug("[MQ] 抢票消息已发送 orderId={}", message.getOrderId());
    }

    /**
     * 投递到延迟队列，TTL 到期后由死信路由到超时队列
     */
    public void sendOrderTimeout(Long orderId) {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.TICKET_EXCHANGE, RabbitMqConfig.DELAY_ROUTING_KEY,
                new OrderTimeoutMessage(orderId));
        log.debug("[MQ] 延迟关单消息已发送 orderId={}", orderId);
    }
}
