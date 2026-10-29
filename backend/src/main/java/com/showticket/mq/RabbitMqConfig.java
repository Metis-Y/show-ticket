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
 *    ├─ ticket.grab   -> ticket.grab.queue     抢票下单
 *    ├─ ticket.delay  -> ticket.delay.queue    延迟队列（无消费者，只靠 TTL 存活）
 *    └─ ticket.timeout-> ticket.timeout.queue  TTL 到期后由死信路由过来
 *
 *  ticket.dlx (direct)
 *    └─ ticket.dead   -> ticket.dead.queue     重试耗尽后的死信
 * </pre>
 *
 * 延迟关单用「TTL + 死信队列」实现：消息先投递到延迟队列，在队列里存活
 * app.order.timeout-ms 毫秒后过期，成为死信被重新路由到超时队列，
 * 消费者此时再检查订单是否已支付。
 */
@Configuration
public class RabbitMqConfig {
    // ========== 交换机名称 ==========
    /** 业务主交换机(direct):抢票、延迟、超时三类消息都经它路由 */
    public static final String TICKET_EXCHANGE = "ticket.exchange";
    /** 死信交换机(direct):接收消费失败/重试耗尽的死信消息 */
    public static final String TICKET_DLX = "ticket.dlx";

    // ========== 抢票下单:队列 & 路由键 ==========
    /** 抢票队列:消费者收到后执行扣减库存、创建订单 */
    public static final String GRAB_QUEUE = "ticket.grab.queue";
    /** 抢票消息的路由键 */
    public static final String GRAB_ROUTING_KEY = "ticket.grab";

    // ========== 延迟队列:队列 & 路由键 ==========
    /** 延迟队列:无消费者,消息仅在此等待 TTL 过期 */
    public static final String DELAY_QUEUE = "ticket.delay.queue";
    /** 延迟消息的路由键 */
    public static final String DELAY_ROUTING_KEY = "ticket.delay";

    // ========== 超时队列:队列 & 路由键 ==========
    /** 超时队列:延迟消息 TTL 到期后经死信路由到此,用于检查订单是否已支付 */
    public static final String TIMEOUT_QUEUE = "ticket.timeout.queue";
    /** 超时消息的路由键 */
    public static final String TIMEOUT_ROUTING_KEY = "ticket.timeout";

    // ========== 死信队列:队列 & 路由键 ==========
    /** 死信队列:存放重试耗尽的失败消息(抢票/超时队列消费失败后投递到此) */
    public static final String DEAD_QUEUE = "ticket.dead.queue";
    /** 死信消息的路由键 */
    public static final String DEAD_ROUTING_KEY = "ticket.dead";

    /** 订单超时时间(毫秒),默认 60 秒,同时作为延迟队列的 TTL */
    @Value("${app.order.timeout-ms:60000}")
    private long timeoutMs;

    /** 业务主交换机:所有业务消息的入口 */
    @Bean
    public DirectExchange ticketExchange() {
        return new DirectExchange(TICKET_EXCHANGE, true, false);
    }

    /** 死信交换机:最终失败消息的入口 */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(TICKET_DLX, true, false);
    }

    /**
     * 抢票队列 —— 绑定到主交换机 ticket.exchange(路由键 ticket.grab)
     * 消费失败重试耗尽后,死信投递到死信交换机 ticket.dlx 的 ticket.dead 路由
     */
    @Bean
    public Queue grabQueue() {
        return QueueBuilder.durable(GRAB_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 超时队列 —— 绑定到主交换机 ticket.exchange(路由键 ticket.timeout)
     * 接收延迟队列 TTL 过期后转投过来的消息;消费失败重试耗尽后同样转投死信交换机
     */
    @Bean
    public Queue timeoutQueue() {
        return QueueBuilder.durable(TIMEOUT_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 延迟队列 —— 绑定到主交换机 ticket.exchange(路由键 ticket.delay)
     * 本身没有消费者:消息只在这里等待 TTL(app.order.timeout-ms)过期,
     * 过期后变成死信被重新路由回主交换机 ticket.exchange 的 ticket.timeout 路由,
     * 也就是转投到超时队列(注意这里死信交换机是业务交换机,不是 ticket.dlx)
     */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl((int) timeoutMs)
                .deadLetterExchange(TICKET_EXCHANGE)
                .deadLetterRoutingKey(TIMEOUT_ROUTING_KEY)
                .build();
    }

    /**
     * 死信队列 —— 绑定到死信交换机 ticket.dlx(路由键 ticket.dead)
     * 最终兜底队列,存放重试耗尽的失败消息,不再配置死信以避免死信循环
     */
    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    /** 绑定:主交换机 ticket.exchange + ticket.grab -> 抢票队列 */
    @Bean
    public Binding grabBinding() {
        return BindingBuilder.bind(grabQueue()).to(ticketExchange()).with(GRAB_ROUTING_KEY);
    }

    /** 绑定:主交换机 ticket.exchange + ticket.delay -> 延迟队列 */
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(ticketExchange()).with(DELAY_ROUTING_KEY);
    }

    /** 绑定:主交换机 ticket.exchange + ticket.timeout -> 超时队列 */
    @Bean
    public Binding timeoutBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(ticketExchange()).with(TIMEOUT_ROUTING_KEY);
    }

    /** 绑定:死信交换机 ticket.dlx + ticket.dead -> 死信队列 */
    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadLetterExchange()).with(DEAD_ROUTING_KEY);
    }

    /**
     * 消息序列化:统一使用 JSON,生产者发送 POJO,消费者可直接声明 POJO 参数接收
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
