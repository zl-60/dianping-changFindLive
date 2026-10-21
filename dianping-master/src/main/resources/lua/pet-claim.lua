-- Keys: stock, buyers, request, pending, metadata. All use the same Redis hash tag.
local existing=redis.call('hget',KEYS[2],ARGV[2])
if existing then return 2 end
local stock=tonumber(redis.call('get',KEYS[1]))
if not stock then return 3 end
local starts=tonumber(redis.call('hget',KEYS[5],'startsAt'))
local ends=tonumber(redis.call('hget',KEYS[5],'endsAt'))
local now=tonumber(ARGV[4])
if not starts or not ends or now<starts or now>=ends then return 4 end
if stock<=0 then return 1 end
redis.call('decr',KEYS[1])
redis.call('hset',KEYS[2],ARGV[2],ARGV[1])
redis.call('hset',KEYS[3],'orderId',ARGV[1],'userId',ARGV[2],'couponId',ARGV[3],'createdAt',ARGV[4],'state','PENDING')
redis.call('zadd',KEYS[4],now,ARGV[1])
return 0
