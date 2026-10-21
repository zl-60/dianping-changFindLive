package com.petcare.platform;

import org.redisson.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Redis Lua records pending work with the stock deduction. Transport is at least once. */
@Component @Profile("platform")
@ConditionalOnProperty(name="pet.relay-enabled",havingValue="true",matchIfMissing=true)
public class PetCouponRelay {
    private static final Logger LOG=LoggerFactory.getLogger(PetCouponRelay.class);
    private static final DefaultRedisScript<Long> DELAY=new DefaultRedisScript<>(
        "if redis.call('hget',KEYS[1],'state')=='PENDING' then redis.call('zadd',KEYS[2],ARGV[2],ARGV[1]) else redis.call('zrem',KEYS[2],ARGV[1]) end return 1",Long.class);
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final RedissonClient locks;
    private long lastWarning;
    public PetCouponRelay(StringRedisTemplate redis,RabbitTemplate rabbit,RedissonClient locks) { this.redis=redis; this.rabbit=rabbit; this.locks=locks; }
    @Scheduled(fixedDelay=1000,initialDelay=5000)
    public void relay() {
        RLock lock=locks.getLock(PetKeys.PREFIX+"relay-lock");
        if(!lock.tryLock()) return;
        try {
            Set<String> due=redis.opsForZSet().rangeByScore(PetKeys.PENDING,0,System.currentTimeMillis(),0,50);
            if(due==null) return;
            for(String id:due) {
                try {
                    MessageProperties properties=new MessageProperties();
                    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
                    properties.setContentEncoding("UTF-8"); properties.setMessageId(id);
                    CorrelationData correlation=new CorrelationData(id+":"+UUID.randomUUID());
                    rabbit.send(PetPlatformConfig.EXCHANGE,PetPlatformConfig.ROUTING,new Message(id.getBytes(StandardCharsets.UTF_8),properties),correlation);
                    if(!correlation.getFuture().get(5,TimeUnit.SECONDS).isAck()||correlation.getReturnedMessage()!=null)
                        throw new IllegalStateException("RabbitMQ 未确认消息路由");
                    redis.execute(DELAY,Arrays.asList(PetKeys.request(id),PetKeys.PENDING),id,String.valueOf(System.currentTimeMillis()+30000));
                } catch(Exception error) {
                    if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                    if(System.currentTimeMillis()-lastWarning>30000) { LOG.warn("优惠券消息暂未送达，待发送记录保留并自动重试: {}",id,error); lastWarning=System.currentTimeMillis(); }
                    break;
                }
            }
        } finally { lock.unlock(); }
    }
}
