-- 验证码发送限流：手机号冷却 + IP 小时配额，整体原子执行
-- KEYS[1] = 手机号冷却 key
-- KEYS[2] = IP 计数 key
-- ARGV[1] = 手机号冷却秒数
-- ARGV[2] = IP 每小时上限
-- ARGV[3] = IP 计数窗口秒数
local phoneKey = KEYS[1]
local ipKey = KEYS[2]

-- 1. 同一手机号冷却中
if redis.call('exists', phoneKey) == 1 then
    return 1
end

-- 2. 同一 IP 是否超出小时配额
local ipCount = tonumber(redis.call('get', ipKey) or '0')
if ipCount >= tonumber(ARGV[2]) then
    return 2
end

-- 3. 记录本次发送：手机号冷却 + IP 计数（首次计数时设置窗口）
redis.call('set', phoneKey, '1', 'EX', tonumber(ARGV[1]))
redis.call('incr', ipKey)
if redis.call('ttl', ipKey) < 0 then
    redis.call('expire', ipKey, tonumber(ARGV[3]))
end
return 0