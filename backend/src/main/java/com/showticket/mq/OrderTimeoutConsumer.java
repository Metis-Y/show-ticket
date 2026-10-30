package com.showticket.mq;

import com.showticket.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 超时关单消费者：延迟队列到期后检查订单，未支付则关单
 */
@Slf4j
@Component
public class OrderTimeoutConsumer {

    @Resource
    private ITicketOrderService ticketOrderService;

    @RabbitListener(queues = RabbitMqConfig.TIMEOUT_QUEUE)
    public void onMessage(OrderTimeoutMessage message) {
        log.info("[MQ] 收到超时关单消息 orderId={}", message.getOrderId());
        ticketOrderService.cancelTimeoutOrder(message.getOrderId());
    }
}
