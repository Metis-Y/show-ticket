package com.showticket;

import com.showticket.entity.TicketOrder;
import com.showticket.mq.TicketOrderMessage;
import com.showticket.service.IShowStockService;
import com.showticket.service.ITicketOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import static com.showticket.utils.RedisConstants.GRAB_STOCK_KEY;
import static com.showticket.utils.RedisConstants.TICKET_ORDER_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MQ 消费者背后的订单核心逻辑（直接调用 service，不依赖 broker）：
 * 落库扣库存 -> 重复消息幂等 -> 超时关单回补 -> 重复关单幂等
 */
@SpringBootTest
@Transactional
class TicketOrderFlowTests {

    private static final Long SHOW_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 9000000000000000001L;

    @Resource
    private ITicketOrderService ticketOrderService;
    @Resource
    private IShowStockService showStockService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void createThenCancelOrder() {
        int stockBefore = showStockService.getById(SHOW_ID).getStock();
        showStockService.warmUp(SHOW_ID);
        try {
            // 模拟 Lua 预扣：限购占位 + Redis 库存 -1
            stringRedisTemplate.opsForSet().add(TICKET_ORDER_KEY + SHOW_ID, USER_ID.toString());
            stringRedisTemplate.opsForValue().decrement(GRAB_STOCK_KEY + SHOW_ID);

            // 1. 消费消息落库
            ticketOrderService.createTicketOrder(new TicketOrderMessage(ORDER_ID, USER_ID, SHOW_ID));
            TicketOrder created = ticketOrderService.getById(ORDER_ID);
            assertNotNull(created);
            assertEquals(1, created.getStatus().intValue());
            assertEquals(stockBefore - 1, showStockService.getById(SHOW_ID).getStock().intValue());

            // 2. 重复消费幂等：库存不能重复扣
            ticketOrderService.createTicketOrder(new TicketOrderMessage(ORDER_ID, USER_ID, SHOW_ID));
            assertEquals(stockBefore - 1, showStockService.getById(SHOW_ID).getStock().intValue());

            // 3. 超时关单：状态 4，DB/Redis 库存回补，限购占位释放
            assertTrue(ticketOrderService.cancelTimeoutOrder(ORDER_ID));
            assertEquals(4, ticketOrderService.getById(ORDER_ID).getStatus().intValue());
            assertEquals(stockBefore, showStockService.getById(SHOW_ID).getStock().intValue());
            assertEquals(String.valueOf(stockBefore),
                    stringRedisTemplate.opsForValue().get(GRAB_STOCK_KEY + SHOW_ID));
            assertFalse(Boolean.TRUE.equals(
                    stringRedisTemplate.opsForSet().isMember(TICKET_ORDER_KEY + SHOW_ID, USER_ID.toString())));

            // 4. 重复关单幂等：库存不能加第二次
            assertTrue(ticketOrderService.cancelTimeoutOrder(ORDER_ID));
            assertEquals(stockBefore, showStockService.getById(SHOW_ID).getStock().intValue());
        } finally {
            // 测试事务会回滚 DB，这里只恢复 Redis
            stringRedisTemplate.opsForSet().remove(TICKET_ORDER_KEY + SHOW_ID, USER_ID.toString());
            showStockService.warmUp(SHOW_ID);
        }
    }
}
