package com.showticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 抢票下单消息：Lua 校验通过后发出，消费者据此落库
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long orderId;

    private Long userId;

    private Long showId;
}
