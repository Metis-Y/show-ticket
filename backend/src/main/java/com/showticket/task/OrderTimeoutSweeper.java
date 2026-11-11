package com.showticket.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.showticket.entity.TicketOrder;
import com.showticket.service.ITicketOrderService;
import com.showticket.service.impl.TicketOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时关单的扫表兜底。
 *
 * <p>正常路径靠「TTL + 死信队列」的延迟消息及时关单；但延迟消息一旦丢失（broker 重启、
 * 路由错、消费失败到底），订单会一直挂「未支付」、票被锁死。这里定时扫
 * 「未支付且已超过超时时间」的订单，复用 {@code cancelTimeoutOrder} 补齐最终保证。</p>
 *
 * <p>复用关单的条件更新（status 1→4）自带幂等：和延迟消息并发关同一单时，只有一次
 * 更新成功、只回补一次库存，所以扫表和延迟消息并存互不冲突。</p>
 */
@Slf4j
@Component
public class OrderTimeoutSweeper {

    @Resource
    private ITicketOrderService ticketOrderService;

    @Value("${app.order.timeout-ms:60000}")
    private long timeoutMs;

    /** 每 30 秒扫一次；兜底晚于延迟消息，最坏晚约 30 秒完成关单 */
    @Scheduled(fixedDelay = 30_000)
    public void sweepTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(timeoutMs / 1000);
        List<TicketOrder> expired = ticketOrderService.list(
                Wrappers.<TicketOrder>lambdaQuery()
                        .eq(TicketOrder::getStatus, TicketOrderServiceImpl.STATUS_UNPAID)
                        .lt(TicketOrder::getCreateTime, deadline));
        if (expired.isEmpty()) {
            return;
        }
        for (TicketOrder order : expired) {
            try {
                ticketOrderService.cancelTimeoutOrder(order.getId());
            } catch (Exception e) {
                // 单笔失败不拖垮整轮扫描，下一轮再补
                log.warn("[关单] 扫表兜底处理异常 orderId={}：{}", order.getId(), e.getMessage());
            }
        }
        log.info("[关单] 扫表兜底扫到 {} 笔超时未支付订单", expired.size());
    }
}