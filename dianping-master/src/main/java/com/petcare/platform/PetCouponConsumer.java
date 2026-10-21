package com.petcare.platform;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.petcare.*;
import com.petcare.platform.mapper.PetCouponMapper;
import org.redisson.api.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import static com.petcare.PetData.map;

@Component @Profile("platform")
public class PetCouponConsumer {
    private static final DefaultRedisScript<Long> FINISH=new DefaultRedisScript<>();
    static { FINISH.setLocation(new ClassPathResource("lua/pet-claim-finish.lua")); FINISH.setResultType(Long.class); }
    private final PetData db;
    private final PetCouponMapper coupons;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final RedissonClient locks;
    private final java.time.Clock clock;
    public PetCouponConsumer(PetData db,PetCouponMapper coupons,org.springframework.transaction.PlatformTransactionManager manager,StringRedisTemplate redis,RedissonClient locks,java.time.Clock clock) {
        this.db=db; this.coupons=coupons; this.tx=new TransactionTemplate(manager); this.redis=redis; this.locks=locks; this.clock=clock;
    }
    @RabbitListener(queues=PetPlatformConfig.QUEUE,autoStartup="${pet.listener-enabled:true}")
    public void consume(String id) {
        long orderId=PetCouponService.parseId(id);
        RLock lock=locks.getLock(PetKeys.PREFIX+"order-lock:"+id); lock.lock();
        try {
            Map<String,Object> result=tx.execute(status->{
                List<Map<String,Object>> existing=db.rows("SELECT * FROM pet_coupon_order WHERE id=?",orderId);
                if(!existing.isEmpty()) return existing.get(0);
                Map<Object,Object> request=redis.opsForHash().entries(PetKeys.request(id));
                if(request.isEmpty()) throw new IllegalStateException("待处理抢券记录不存在: "+id);
                long user=Long.parseLong(request.get("userId").toString()),couponId=Long.parseLong(request.get("couponId").toString());
                Timestamp created=Timestamp.valueOf(java.time.Instant.ofEpochMilli(Long.parseLong(request.get("createdAt").toString())).atZone(clock.getZone()).toLocalDateTime());
                int changed=coupons.update(null,new UpdateWrapper<PetCoupon>().eq("id",couponId).eq("published",1).gt("stock",0)
                    .le("starts_at",created).gt("ends_at",created).setSql("stock=stock-1"));
                String state=changed==1?"SUCCESS":"FAILED",reason=changed==1?"":"数据库库存不足或活动不可用";
                db.update("INSERT INTO pet_coupon_order(id,user_id,coupon_id,status,reason,created_at) VALUES(?,?,?,?,?,?)",orderId,user,couponId,state,reason,created);
                return map("userId",user,"couponId",couponId,"status",state,"reason",reason);
            });
            long couponId=((Number)result.get("couponId")).longValue();
            String state="FAILED".equals(result.get("status"))?"FAILED":"SUCCESS";
            redis.execute(FINISH,Arrays.asList(PetKeys.coupon(couponId,"stock"),PetKeys.coupon(couponId,"buyers"),PetKeys.request(id),PetKeys.PENDING),
                id,result.get("userId").toString(),state,String.valueOf(result.getOrDefault("reason","")));
        } finally { lock.unlock(); }
    }
}
