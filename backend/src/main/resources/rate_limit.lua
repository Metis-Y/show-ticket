-- 令牌桶限流：Redis 内原子「按时间补令牌 + 判断 + 扣令牌」
-- KEYS[1] = 限流 key
-- ARGV[1] = 桶容量  ARGV[2] = 每秒补发速率  ARGV[3] = 当前毫秒  ARGV[4] = 本次消耗令牌数
-- 返回 1=放行  0=拒绝
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil then
    tokens = capacity
    ts = now
end

-- 按流逝时间补发令牌，最多补到桶容量
tokens = math.min(capacity, tokens + (now - ts) / 1000 * rate)

if tokens >= requested then
    redis.call('HMSET', key, 'tokens', tokens - requested, 'ts', now)
    redis.call('PEXPIRE', key, 5000)
    return 1
end
redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
redis.call('PEXPIRE', key, 5000)
return 0