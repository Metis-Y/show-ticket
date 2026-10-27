package com.showticket.mq;

import com.showticket.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 抢票下单消费者
 * <p>
 * 失败重试由 spring.rabbitmq.listener.simple.retry 控制（默认 3 次，指数退避），
 * 重试耗尽后 default-requeue-rejected=false 使消息进入死信队列 ticket.dead.queue。
 * 幂等由订单号保证：同一订单号重复消费时先查后插，直接忽略。
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
