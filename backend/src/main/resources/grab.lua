-- 抢票预校验：开售时间 + 库存 + 一人一票，Redis 内原子执行
-- ARGV[1] = key 前缀, ARGV[2] = showId, ARGV[3] = userId, ARGV[4] = 当前 epoch 秒
local prefix = ARGV[1]
local showId = ARGV[2]
local userId = ARGV[3]
local now = tonumber(ARGV[4])

local stockKey = prefix .. 'ticket:stock:' .. showId
local orderKey = prefix .. 'ticket:order:' .. showId
local windowKey = prefix .. 'ticket:window:' .. showId

-- 0. 未预热
local stock = redis.call('get', stockKey)
if (stock == false) then
    return 5
end

-- 1. 开售时间校验
local beginTs = tonumber(redis.call('hget', windowKey, 'begin') or '0')
local endTs = tonumber(redis.call('hget', windowKey, 'end') or '0')
if (beginTs > 0 and now < beginTs) then
    return 3
end
if (endTs > 0 and now > endTs) then
    return 4
end

-- 2. 库存校验
if (tonumber(stock) <= 0) then
    return 1
end

-- 3. 一人一票校验
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

-- 4. 预扣库存 + 占位
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0