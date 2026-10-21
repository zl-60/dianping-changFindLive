-- Completion is retriable. Database result is committed BEFORE calling this script.
if redis.call('hget',KEYS[3],'state')~='PENDING' then
    redis.call('zrem',KEYS[4],ARGV[1])
    return 0
end
if ARGV[3]=='FAILED' then
    redis.call('incr',KEYS[1])
end
redis.call('hset',KEYS[3],'state',ARGV[3],'reason',ARGV[4])
redis.call('expire',KEYS[3],604800)
redis.call('zrem',KEYS[4],ARGV[1])
return 1
