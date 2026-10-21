package com.petcare.platform;

import com.hmdp.utils.RedisIdWorker;
import com.petcare.*;
import org.redisson.api.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.petcare.PetData.*;

@Service @Profile("platform")
public class PetCouponService {
    private static final DefaultRedisScript<Long> CLAIM=new DefaultRedisScript<>();
    static { CLAIM.setLocation(new ClassPathResource("lua/pet-claim.lua")); CLAIM.setResultType(Long.class); }
    private final PetData db;
    private final StringRedisTemplate redis;
    private final RedisIdWorker ids;
    private final RedissonClient locks;
    private final Clock clock;
    public PetCouponService(PetData db,StringRedisTemplate redis,RedisIdWorker ids,RedissonClient locks,Clock clock) {
        this.db=db; this.redis=redis; this.ids=ids; this.locks=locks; this.clock=clock;
    }
    public long member(String phone) { return ((Number)db.one("SELECT id FROM pet_member WHERE phone=?",phone).get("id")).longValue(); }
    public List<Map<String,Object>> list() {
        List<Map<String,Object>> rows=db.rows("SELECT c.*,p.name AS place_name FROM pet_coupon c JOIN pet_place p ON p.id=c.place_id WHERE published=1 ORDER BY c.id DESC");
        for(Map<String,Object> row:rows) {
            String stock=redis.opsForValue().get(PetKeys.coupon(((Number)row.get("id")).longValue(),"stock"));
            if(stock!=null) row.put("stock",Integer.parseInt(stock));
        }
        return rows;
    }
    public Map<String,Object> detail(long id) { return db.one("SELECT * FROM pet_coupon WHERE id=? AND published=1",id); }
    public List<Map<String,Object>> ownerList() { return db.rows("SELECT * FROM pet_coupon ORDER BY id DESC"); }
    public Map<String,Object> create(Map<String,Object> input) {
        String title=text(input,"title",80);
        int stock=number(input,"stock",1,100000),discount=number(input,"discountCents",1,1000000),minimum=number(input,"minSpendCents",0,1000000);
        LocalDateTime start=parse(text(input,"startsAt",30)),end=parse(text(input,"endsAt",30)),valid=parse(text(input,"validUntil",30));
        if(!end.isAfter(start)||!valid.isAfter(end)||!end.isAfter(LocalDateTime.now(clock))) throw new PetException("活动结束时间、有效期应依次递增，且活动尚未结束");
        db.update("INSERT INTO pet_coupon(place_id,title,discount_cents,min_spend_cents,stock,starts_at,ends_at,valid_until,published) VALUES(1,?,?,?,?,?,?,?,0)",title,discount,minimum,stock,Timestamp.valueOf(start),Timestamp.valueOf(end),Timestamp.valueOf(valid));
        return map("created",true);
    }
    public void publish(long id) {
        RLock lock=locks.getLock(PetKeys.coupon(id,"publish-lock")); lock.lock();
        try {
            Map<String,Object> row=db.one("SELECT * FROM pet_coupon WHERE id=?",id);
            if(!Boolean.TRUE.equals(redis.hasKey(PetKeys.coupon(id,"stock")))) {
                Map<String,String> meta=new HashMap<>();
                meta.put("startsAt",String.valueOf(epoch((String)row.get("startsAt"))));
                meta.put("endsAt",String.valueOf(epoch((String)row.get("endsAt"))));
                redis.opsForHash().putAll(PetKeys.coupon(id,"meta"),meta);
                for(Map<String,Object> order:db.rows("SELECT id,user_id FROM pet_coupon_order WHERE coupon_id=?",id))
                    redis.opsForHash().put(PetKeys.coupon(id,"buyers"),order.get("userId").toString(),order.get("id").toString());
                redis.opsForValue().setIfAbsent(PetKeys.coupon(id,"stock"),row.get("stock").toString());
            }
            db.update("UPDATE pet_coupon SET published=1 WHERE id=?",id);
        } finally { lock.unlock(); }
    }
    public Map<String,Object> claim(long couponId,String phone) {
        detail(couponId);
        long user=member(phone); String orderId=ids.nextId("pet:coupon").toString();
        Long result=redis.execute(CLAIM,Arrays.asList(PetKeys.coupon(couponId,"stock"),PetKeys.coupon(couponId,"buyers"),PetKeys.request(orderId),PetKeys.PENDING,PetKeys.coupon(couponId,"meta")),
            orderId,String.valueOf(user),String.valueOf(couponId),String.valueOf(clock.millis()));
        if(result==null) throw new PetException(503,"抢券服务暂时不可用");
        if(result==2) {
            Object existing=redis.opsForHash().get(PetKeys.coupon(couponId,"buyers"),String.valueOf(user));
            return result(String.valueOf(existing),phone);
        }
        if(result==1) throw new PetException(409,"优惠券已抢完");
        if(result==3) throw new PetException(503,"活动库存尚未就绪，请店主重新发布活动");
        if(result==4) throw new PetException(409,"活动尚未开始或已经结束");
        if(result!=0) throw new PetException(503,"抢券服务暂时不可用");
        return map("orderId",orderId,"state","PENDING","message","抢券请求已受理，正在生成优惠券");
    }
    public Map<String,Object> result(String id,String phone) {
        long user=member(phone);
        List<Map<String,Object>> orders=db.rows("SELECT o.*,c.title,c.discount_cents,c.min_spend_cents,c.valid_until FROM pet_coupon_order o JOIN pet_coupon c ON c.id=o.coupon_id WHERE o.id=?",parseId(id));
        if(!orders.isEmpty()) {
            Map<String,Object> row=orders.get(0);
            if(((Number)row.get("userId")).longValue()!=user) throw new PetException(403,"只能查看自己的抢券结果");
            row.put("orderId",row.get("id").toString()); row.put("state",row.get("status")); return row;
        }
        Map<Object,Object> pending=redis.opsForHash().entries(PetKeys.request(id));
        if(pending.isEmpty()) throw new PetException(404,"抢券记录不存在");
        if(!String.valueOf(user).equals(pending.get("userId"))) throw new PetException(403,"只能查看自己的抢券结果");
        return map("orderId",id,"state",pending.get("state"),"reason",pending.get("reason"));
    }
    public List<Map<String,Object>> mine(String phone) {
        return db.rows("SELECT o.id,o.status,o.redeemed_order_id,c.title,c.discount_cents,c.min_spend_cents,c.valid_until FROM pet_coupon_order o JOIN pet_coupon c ON c.id=o.coupon_id WHERE o.user_id=? ORDER BY o.created_at DESC",member(phone));
    }
    public static long parseId(String value) {
        try { long id=Long.parseLong(value); if(id<=0) throw new NumberFormatException(); return id; }
        catch(RuntimeException error) { throw new PetException("无效的记录编号"); }
    }
    private LocalDateTime parse(String value) {
        try { return LocalDateTime.parse(value); } catch(RuntimeException error) { throw new PetException("时间格式不正确"); }
    }
    private long epoch(String value) { return LocalDateTime.parse(value).atZone(clock.getZone()).toInstant().toEpochMilli(); }
}
