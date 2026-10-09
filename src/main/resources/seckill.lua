-- 抢票资格判断脚本：原子完成 库存校验 + 一人一单 + 扣减库存
-- KEYS 不使用，全部通过 ARGV 传参，便于按票档动态拼 key

-- ARGV[1] 票档id
-- ARGV[2] 用户id
-- ARGV[3] 订单id
local tierId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

-- 1. 库存 key（String）与已购用户 key（Set）
local stockKey = 'seckill:stock:' .. tierId
local orderKey = 'seckill:order:' .. tierId

-- 2. 校验库存是否已预热
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    -- 未开票 / 未预热
    return -1
end

-- 3. 库存不足
if stock <= 0 then
    return 1
end

-- 4. 一人一单：用户是否已在已购集合中
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 5. 扣减库存
redis.call('incrby', stockKey, -1)
-- 6. 记录该用户已抢到（一人一单）
redis.call('sadd', orderKey, userId)

-- 返回 0 表示抢票资格校验通过
return 0
