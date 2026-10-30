package com.showticket.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑
 *
 * <pre>
 *  ticket.exchange (direct)
 *    ├─ ticket.grab    -> ticket.grab.queue     抢票下单
 *    ├─ ticket.delay   -> ticket.delay.queue    延迟队列（无消费者，靠 TTL 存活）
 *    └─ ticket.timeout -> ticket.timeout.queue  超时关单
 *
 *  ticket.dlx (direct)
 *    └─ ticket.dead    -> ticket.dead.queue     重试耗尽后的死信
 * </pre>
 *
 * 延迟关单：消息先进延迟队列，TTL 到期后作为死信路由到超时队列（而非死信队列），
 * 由消费者检查订单是否已支付。
 */
@Configuration
public class RabbitMqConfig {
    // ========== 交换机 ==========
    /** 业务主交换机 */
    public static final String TICKET_EXCHANGE = "ticket.exchange";
    /** 死信交换机 */
    public static final String TICKET_DLX = "ticket.dlx";

    // ========== 抢票下单 ==========
    /** 抢票队列：消费者扣减库存、创建订单 */
    public static final String GRAB_QUEUE = "ticket.grab.queue";
    /** 抢票路由键 */
    public static final String GRAB_ROUTING_KEY = "ticket.grab";

    // ========== 延迟队列 ==========
    /** 延迟队列：不消费，只等 TTL 过期 */
    public static final String DELAY_QUEUE = "ticket.delay.queue";
    /** 延迟路由键 */
    public static final String DELAY_ROUTING_KEY = "ticket.delay";

    // ========== 超时队列 ==========
    /** 超时队列：延迟到期后关单 */
    public static final String TIMEOUT_QUEUE = "ticket.timeout.queue";
    /** 超时路由键 */
    public static final String TIMEOUT_ROUTING_KEY = "ticket.timeout";

    // ========== 死信队列 ==========
    /** 死信队列：兜底存放重试耗尽的失败消息 */
    public static final String DEAD_QUEUE = "ticket.dead.queue";
    /** 死信路由键 */
    public static final String DEAD_ROUTING_KEY = "ticket.dead";

    /** 订单超时时间(毫秒)，同时作为延迟队列的 TTL */
    @Value("${app.order.timeout-ms:60000}")
    private long timeoutMs;

    /** 业务主交换机：抢票、延迟、超时消息的入口 */
    @Bean
    public DirectExchange ticketExchange() {
        return new DirectExchange(TICKET_EXCHANGE, true, false);
    }

    /** 死信交换机：接收消费失败的消息 */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(TICKET_DLX, true, false);
    }

    /** 抢票队列：消费失败重试耗尽后转投死信队列 */
    @Bean
    public Queue grabQueue() {
        return QueueBuilder.durable(GRAB_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /** 超时队列：接收延迟队列到期消息，消费失败转投死信队列 */
    @Bean
    public Queue timeoutQueue() {
        return QueueBuilder.durable(TIMEOUT_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /** 延迟队列：无消费者，TTL 到期后转投超时队列（死信交换机指向业务交换机） */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl((int) timeoutMs)  //过期时间
                .deadLetterExchange(TICKET_EXCHANGE)
                .deadLetterRoutingKey(TIMEOUT_ROUTING_KEY)
                .build();
    }

    /** 死信队列：最终兜底，不再配置死信以避免循环 */
    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    /** ticket.grab -> 抢票队列 */
    @Bean
    public Binding grabBinding() {
        return BindingBuilder.bind(grabQueue()).to(ticketExchange()).with(GRAB_ROUTING_KEY);
    }

    /** ticket.delay -> 延迟队列 */
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(ticketExchange()).with(DELAY_ROUTING_KEY);
    }

    /** ticket.timeout -> 超时队列 */
    @Bean
    public Binding timeoutBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(ticketExchange()).with(TIMEOUT_ROUTING_KEY);
    }

    /** ticket.dead -> 死信队列 */
    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadLetterExchange()).with(DEAD_ROUTING_KEY);
    }

    /** 统一 JSON 序列化，收发双方直接用 POJO */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
