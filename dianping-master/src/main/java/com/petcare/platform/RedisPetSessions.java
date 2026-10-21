package com.petcare.platform;

import com.petcare.PetSessions;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component @Profile("platform")
public class RedisPetSessions implements PetSessions {
    private final StringRedisTemplate redis;
    public RedisPetSessions(StringRedisTemplate redis) { this.redis=redis; }
    private String key(String token) { return PetKeys.PREFIX+"session:"+token; }
    public void save(String token,Map<String,Object> user) {
        redis.execute(new DefaultRedisScript<>("redis.call('hset',KEYS[1],'subject',ARGV[1],'role',ARGV[2]); redis.call('expire',KEYS[1],1800); return 1",Long.class),
            Collections.singletonList(key(token)),user.get("subject").toString(),user.get("role").toString());
    }
    public Map<String,Object> load(String token) {
        if(!token.matches("[a-f0-9]{64}")) return null;
        Map<Object,Object> values=redis.opsForHash().entries(key(token));
        if(values.isEmpty()) return null;
        redis.expire(key(token),30,TimeUnit.MINUTES);
        Map<String,Object> user=new LinkedHashMap<>();
        values.forEach((name,value)->user.put(name.toString(),value));
        return user;
    }
    public void delete(String token) { redis.delete(key(token)); }
}
