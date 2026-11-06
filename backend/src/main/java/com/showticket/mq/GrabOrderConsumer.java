package com.showticket.mq;

import com.showticket.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 抢票下单消费者：扣减库存并创建订单
 * 重试耗尽后消息进入死信队列；幂等由订单号保证，重复消费直接忽略。
 */
@Slf4j
@Component
public class GrabOrderConsumer {

    @Resource
    private ITicketOrderService ticketOrderService;

    @RabbitListener(queues = RabbitMqConfig.GRAB_QUEUE)
    public void onMessage(TicketOrderMessage message) {
        log.info("[MQ] 收到抢票消息 orderId={}, userId={}, showId={}",
                message.getOrderId(), message.getUserId(), message.getShowId());
        ticketOrderService.createTicketOrder(message);
    }
}
