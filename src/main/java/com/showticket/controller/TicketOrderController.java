package com.showticket.controller;


import com.showticket.dto.Result;
import com.showticket.service.ITicketOrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author show-ticket
 * @since 2026-01-01
 */
@RestController
@RequestMapping("/ticket-order")
public class TicketOrderController {

    @Resource
    private ITicketOrderService ticketOrderService;

    /**
     * 抢票（Redis + Lua 预扣库存，MQ 异步落库）
     */
    @PostMapping("grab/{id}")
    public Result grabTicket(@PathVariable("id") Long showId) {
        return ticketOrderService.grabTicket(showId);
    }

    /**
     * 查询订单详情
     */
    @GetMapping("/{id}")
    public Result queryOrderById(@PathVariable("id") Long orderId) {
        return ticketOrderService.queryOrderById(orderId);
    }

    /**
     * 我的订单
     */
    @GetMapping("/of/me")
    public Result queryMyOrders() {
        return ticketOrderService.queryMyOrders();
    }

    /**
     * 支付订单
     */
    @PostMapping("/pay/{id}")
    public Result payOrder(@PathVariable("id") Long orderId) {
        return ticketOrderService.payOrder(orderId);
    }
}
