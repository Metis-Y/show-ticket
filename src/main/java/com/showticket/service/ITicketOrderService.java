package com.showticket.service;

import com.showticket.dto.Result;
import com.showticket.entity.TicketOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import com.showticket.mq.TicketOrderMessage;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
public interface ITicketOrderService extends IService<TicketOrder> {

    /**
     * 抢票：Redis + Lua 预扣库存后发送 MQ 异步下单
     *
     * @param showId 演出 id
     * @return 订单 id
     */
    Result grabTicket(Long showId);

    /**
     * 消费抢票消息，真正落库（幂等）
     */
    void createTicketOrder(TicketOrderMessage message);

    /**
     * 支付订单
     */
    Result payOrder(Long orderId);

    /**
     * 超时未支付关单，回补库存（幂等）
     *
     * @return true 表示处理完成，可以 ack
     */
    boolean cancelTimeoutOrder(Long orderId);

    /**
     * 查询订单详情（只能查自己的）
     */
    Result queryOrderById(Long orderId);

    /**
     * 查询我的订单
     */
    Result queryMyOrders();
}
