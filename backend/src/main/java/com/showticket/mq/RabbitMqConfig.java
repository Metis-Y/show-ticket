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
    //每组的
    //第一行是正常消息队列
    //第二行是超时队列
    public static final String TICKET_EXCHANGE = "ticket.exchange";
    public static final String TICKET_DLX = "ticket.dlx";

    public static final String GRAB_QUEUE = "ticket.grab.queue";
    public static final String GRAB_ROUTING_KEY = "ticket.grab";

    public static final String DELAY_QUEUE = "ticket.delay.queue";
    public static final String DELAY_ROUTING_KEY = "ticket.delay";

    public static final String TIMEOUT_QUEUE = "ticket.timeout.queue";
    public static final String TIMEOUT_ROUTING_KEY = "ticket.timeout";

    public static final String DEAD_QUEUE = "ticket.dead.queue";
    public static final String DEAD_ROUTING_KEY = "ticket.dead";

    @Value("${app.order.timeout-ms:60000}")
    private long timeoutMs;

    //交换机
    @Bean
    public DirectExchange ticketExchange() {
        return new DirectExchange(TICKET_EXCHANGE, true, false);
    }
    //超时交换机
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(TICKET_DLX, true, false);
    }

    @Bean
    public Queue grabQueue() {
        return QueueBuilder.durable(GRAB_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue timeoutQueue() {
        return QueueBuilder.durable(TIMEOUT_QUEUE)
                .deadLetterExchange(TICKET_DLX)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 延迟队列本身没有消费者：消息只在这里等待 TTL 过期，然后变成死信被转投到超时队列
     */
    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl((int) timeoutMs)
                .deadLetterExchange(TICKET_EXCHANGE)
                .deadLetterRoutingKey(TIMEOUT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding grabBinding() {
        return BindingBuilder.bind(grabQueue()).to(ticketExchange()).with(GRAB_ROUTING_KEY);
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(ticketExchange()).with(DELAY_ROUTING_KEY);
    }

    @Bean
    public Binding timeoutBinding() {
        return BindingBuilder.bind(timeoutQueue()).to(ticketExchange()).with(TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public Binding deadBinding() {
        return BindingBuilder.bind(deadQueue()).to(deadLetterExchange()).with(DEAD_ROUTING_KEY);
    }

    /**
     * 消息体用 JSON，消费者可直接声明为 POJO 参数
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
