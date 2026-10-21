package com.petcare;

import com.hmdp.utils.*;
import com.petcare.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.redisson.api.RedissonClient;
import org.springframework.test.web.servlet.MockMvc;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.petcare.PetData.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses a real, isolated Redis server. The broker boundary is tested explicitly below. */
@EnabledIfSystemProperty(named="pet.platform.tests",matches="true")
@AutoConfigureMockMvc
@SpringBootTest(classes={PetApplication.class,PetPlatformIntegrationTest.TimeConfig.class},properties={
    "spring.config.name=pet","spring.profiles.active=demo,platform",
    "spring.datasource.url=jdbc:h2:mem:pet-platform;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "spring.redis.port=${pet.test.redis-port:16379}","spring.redis.database=15",
    "pet.expiry-enabled=false","pet.relay-enabled=false","pet.listener-enabled=false","pet.social-relay-enabled=false"
})
class PetPlatformIntegrationTest {
    static class TestClock extends Clock {
        Instant now=Instant.parse("2026-10-21T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock platformClock(){return new TestClock();} }
    @Autowired PetData db;
    @Autowired PetAuth auth;
    @Autowired PetCouponService coupons;
    @Autowired PetCouponConsumer consumer;
    @Autowired PetSocialService social;
    @Autowired PetCatalog catalog;
    @Autowired PetBookingService booking;
    @Autowired CacheClient cache;
    @Autowired RedisIdWorker ids;
    @Autowired StringRedisTemplate redis;
    @Autowired RedissonClient locks;
    @Autowired MockMvc http;
    @Autowired TestClock clock;
    @BeforeEach void reset() {
        clock.now=Instant.parse("2026-10-21T00:00:00Z");
        Set<String> keys=redis.keys(PetKeys.PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        for(String table:Arrays.asList("pet_coupon_use","pet_coupon_history","pet_review","pet_slot","pet_order","pet_coupon_order","pet_coupon","pet_like","pet_follow","pet_social_task","pet_post","pet_login_code","pet_session"))db.update("DELETE FROM "+table);
        for(Map<String,Object> place:db.rows("SELECT id,longitude,latitude FROM pet_place"))
            redis.opsForGeo().add(PetKeys.GEO,new org.springframework.data.geo.Point(((Number)place.get("longitude")).doubleValue(),((Number)place.get("latitude")).doubleValue()),place.get("id").toString());
    }
    String phone(int n) {return String.format("138%08d",n);}
    long member(int n) {
        String phone=phone(n);
        if(db.count("SELECT COUNT(*) FROM pet_member WHERE phone=?",phone)==0)
            db.update("INSERT INTO pet_member(phone,nickname,created_at) VALUES(?,?,?)",phone,"测试宠友"+n,Timestamp.valueOf(LocalDateTime.now(clock)));
        return social.member(phone);
    }
    long coupon(int stock) {
        coupons.create(map("title","测试洗护券","stock",stock,"discountCents",2000,"minSpendCents",6900,
            "startsAt","2026-10-20T00:00","endsAt","2026-10-28T00:00","validUntil","2026-11-21T00:00"));
        long id=((Number)db.one("SELECT MAX(id) AS id FROM pet_coupon").get("id")).longValue();coupons.publish(id);return id;
    }
    String claim(long coupon,int user) {member(user);return coupons.claim(coupon,phone(user)).get("orderId").toString();}
    Map<String,Object> bookingInput(String coupon) {
        long spec=((Number)db.one("SELECT id FROM pet_spec WHERE category='DOG_BATH' ORDER BY id LIMIT 1").get("id")).longValue();
        return map("requestKey",UUID.randomUUID().toString(),"specId",spec,"startsAt","2026-10-22T11:00",
            "species","DOG","petName","豆豆","breed","比熊","weight",5,"contactName","家长","contactPhone",phone(1),"couponOrderId",coupon);
    }
    @Test void luaNeverOversellsAndConsumerIsIdempotent() throws Exception {
        long coupon=coupon(5);for(int i=1;i<=32;i++)member(i);
        ExecutorService pool=Executors.newFixedThreadPool(12);List<Future<String>> futures=new ArrayList<>();
        try {
            for(int i=1;i<=32;i++){final int user=i;futures.add(pool.submit(()->{try{return coupons.claim(coupon,phone(user)).get("orderId").toString();}catch(PetException sold){assertEquals(409,sold.status);return null;}}));}
            List<String> accepted=new ArrayList<>();for(Future<String> future:futures){String id=future.get(20,TimeUnit.SECONDS);if(id!=null)accepted.add(id);}
            assertEquals(5,accepted.size());assertEquals("0",redis.opsForValue().get(PetKeys.coupon(coupon,"stock")));
            assertEquals(5L,redis.opsForZSet().zCard(PetKeys.PENDING));
            for(String id:accepted){consumer.consume(id);consumer.consume(id);}
            assertEquals(5,db.count("SELECT COUNT(*) FROM pet_coupon_order WHERE status='SUCCESS'"));
            assertEquals(0,db.count("SELECT stock FROM pet_coupon WHERE id=?",coupon));
            assertEquals(0L,redis.opsForZSet().zCard(PetKeys.PENDING));
        }finally{pool.shutdownNow();}
    }
    @Test void duplicateClaimsReturnTheSameOrder() throws Exception {
        long coupon=coupon(10);member(1);
        ExecutorService pool=Executors.newFixedThreadPool(8);Set<String> ids=new HashSet<>();List<Future<String>> calls=new ArrayList<>();
        try{for(int i=0;i<16;i++)calls.add(pool.submit(()->claim(coupon,1)));for(Future<String> call:calls)ids.add(call.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(1,ids.size());assertEquals("9",redis.opsForValue().get(PetKeys.coupon(coupon,"stock")));
        String id=ids.iterator().next();consumer.consume(id);consumer.consume(id);
        assertEquals(1,db.count("SELECT COUNT(*) FROM pet_coupon_order"));assertEquals(id,claim(coupon,1));
        assertThrows(PetException.class,()->{member(2);coupons.result(id,phone(2));});
    }
    @Test void databaseRejectionCompensatesOnlyOnce() {
        long coupon=coupon(2);db.update("UPDATE pet_coupon SET stock=0 WHERE id=?",coupon);
        String id=claim(coupon,1);consumer.consume(id);consumer.consume(id);
        assertEquals("FAILED",coupons.result(id,phone(1)).get("state"));
        assertEquals("2",redis.opsForValue().get(PetKeys.coupon(coupon,"stock")));
        assertEquals(0,db.count("SELECT stock FROM pet_coupon WHERE id=?",coupon));
        assertEquals(id,claim(coupon,1));
    }
    @Test void pendingWorkSurvivesPublishFailureAndFastConsumerCompletion() {
        long coupon=coupon(2);String id=claim(coupon,1);
        RabbitTemplate unavailable=new RabbitTemplate(){@Override public void send(String exchange,String routing,Message message,CorrelationData data){throw new IllegalStateException("broker unavailable");}};
        new PetCouponRelay(redis,unavailable,locks).relay();
        assertNotNull(redis.opsForZSet().score(PetKeys.PENDING,id));
        RabbitTemplate delivered=new RabbitTemplate(){@Override public void send(String exchange,String routing,Message message,CorrelationData data){consumer.consume(id);data.getFuture().set(new CorrelationData.Confirm(true,null));}};
        new PetCouponRelay(redis,delivered,locks).relay();
        assertNull(redis.opsForZSet().score(PetKeys.PENDING,id));assertEquals("SUCCESS",coupons.result(id,phone(1)).get("state"));
    }
    @Test void couponDiscountAndReturnShareBookingTransaction() {
        long coupon=coupon(3);String id=claim(coupon,1);consumer.consume(id);
        Map<String,Object> input=bookingInput(id);Map<String,Object> order=booking.create(phone(1),input);
        assertEquals(4900,((Number)order.get("priceCents")).intValue());assertEquals("USED",coupons.result(id,phone(1)).get("state"));
        assertEquals(order.get("id"),booking.create(phone(1),input).get("id"));
        assertThrows(PetException.class,()->booking.create(phone(1),bookingInput(id)));
        assertEquals(1,db.count("SELECT COUNT(*) FROM pet_order"));
        booking.cancel(phone(1),order.get("id").toString());assertEquals("SUCCESS",coupons.result(id,phone(1)).get("state"));
        assertEquals("CANCELLED",booking.create(phone(1),input).get("status"));
        assertNotNull(booking.order(phone(1),order.get("id").toString(),false).get("coupon"));
        assertEquals(4900,((Number)booking.create(phone(1),bookingInput(id)).get("priceCents")).intValue());
    }
    @Test void redisSessionRefreshesAndRequestContextIsCleared() throws Exception {
        String code=auth.code(phone(1)).get("demoCode").toString();String token=auth.customerLogin(phone(1),code).get("token").toString();
        String key=PetKeys.PREFIX+"session:"+token;redis.expire(key,2,TimeUnit.SECONDS);
        http.perform(get("/api/pet/services").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertTrue(redis.getExpire(key,TimeUnit.SECONDS)>1700);assertNull(PetUserContext.get());
        http.perform(get("/api/pet/owner/coupons").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());assertNull(PetUserContext.get());
        http.perform(post("/api/pet/auth/logout").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        http.perform(get("/api/pet/orders").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        http.perform(get("/api/pet/places")).andExpect(status().isOk());
    }
    @Test void cacheUsesNullEntriesAndSingleRebuildAndOwnershipSafeUnlock() throws Exception {
        assertThrows(PetException.class,()->catalog.place(999999));assertEquals("",redis.opsForValue().get(PetKeys.PREFIX+"cache:place:999999"));
        AtomicInteger reads=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<?>> calls=new ArrayList<>();
        try{for(int i=0;i<8;i++)calls.add(pool.submit(()->cache.queryWithMutex(PetKeys.PREFIX+"cache:test:",1,Map.class,key->{reads.incrementAndGet();try{Thread.sleep(60);}catch(InterruptedException error){Thread.currentThread().interrupt();}return map("name","洗护");},1L,TimeUnit.MINUTES)));for(Future<?> call:calls)call.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1,reads.get());
        String name=PetKeys.PREFIX+"ownership";SimpleRedisLock lock=new SimpleRedisLock(name,redis);assertTrue(lock.tryLock(10));
        redis.opsForValue().set("lock"+name,"another-owner");lock.delLock();assertEquals("another-owner",redis.opsForValue().get("lock"+name));redis.delete("lock"+name);
    }
    @Test void geoResultsAreSortedAndValidated() {
        List<Map<String,Object>> nearby=catalog.nearby(120.155070,30.274084,5,1);assertEquals(3,nearby.size());assertEquals(1,((Number)nearby.get(0).get("id")).intValue());
        assertTrue(((Number)nearby.get(0).get("distanceKm")).doubleValue()<=((Number)nearby.get(1).get("distanceKm")).doubleValue());
        assertThrows(PetException.class,()->catalog.nearby(Double.NaN,30,5,1));
    }
    @Test void followsCommonLikesAndFeedHandleEqualTimestamps() {
        long a=member(1),b=member(2),c=member(3);
        social.follow(a,b,true);social.follow(c,b,true);assertEquals(1,social.common(a,c).size());
        for(int i=0;i<12;i++)social.publish(b,map("title","宠物日常 "+i,"content","今天带毛孩子散步。"));
        Timestamp same=Timestamp.valueOf(LocalDateTime.now(clock));db.update("UPDATE pet_post SET created_at=?",same);db.update("UPDATE pet_social_task SET created_at=?",same);
        social.deliverPosts();social.deliverPosts();
        Map<String,Object> first=social.feed(a,Long.MAX_VALUE,0);List<?> firstItems=(List<?>)first.get("items");assertEquals(10,firstItems.size());
        Map<String,Object> next=social.feed(a,((Number)first.get("max")).longValue(),((Number)first.get("offset")).intValue());assertEquals(2,((List<?>)next.get("items")).size());
        Set<Object> distinct=new HashSet<>();for(Object row:firstItems)distinct.add(((Map<?,?>)row).get("id"));for(Object row:(List<?>)next.get("items"))distinct.add(((Map<?,?>)row).get("id"));assertEquals(12,distinct.size());
        long post=((Number)((Map<?,?>)firstItems.get(0)).get("id")).longValue();social.like(a,post,true);social.like(a,post,true);
        assertEquals(1L,redis.opsForZSet().zCard(PetKeys.likes(post)));assertEquals(1,social.likers(post).size());assertFalse(social.hot(a).isEmpty());
        social.like(a,post,false);assertTrue(social.likers(post).isEmpty());social.follow(a,b,false);assertTrue(((List<?>)social.feed(a,Long.MAX_VALUE,0).get("items")).isEmpty());
        assertThrows(PetException.class,()->social.follow(a,a,true));
    }
    @Test void bitmapSignIsIdempotentAndCountsConsecutiveDays() {
        long user=member(1);social.sign(user,true);assertEquals(1,social.sign(user,true).get("monthCount"));
        clock.now=clock.now.plus(Duration.ofDays(1));Map<String,Object> sign=social.sign(user,true);
        assertEquals(2,sign.get("monthCount"));assertEquals(2,sign.get("continuousDaysInMonth"));
        clock.now=clock.now.plus(Duration.ofDays(2));assertEquals(0,social.sign(user,false).get("continuousDaysInMonth"));
        assertEquals(1,social.sign(user,true).get("continuousDaysInMonth"));
    }
    @Test void catalogCacheIsInvalidatedAfterOwnerChange() {
        long id=((Number)db.one("SELECT id FROM pet_spec WHERE category='DOG_BATH' ORDER BY id LIMIT 1").get("id")).longValue();
        Map<String,Object> update=new LinkedHashMap<>(catalog.spec(id));update.put("title","更新后的洗护套餐");
        booking.saveSpec(id,update);assertEquals("更新后的洗护套餐",catalog.spec(id).get("title"));
    }
    @Test void redisIdsRemainUniqueAcrossConcurrentCalls() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<Long>> calls=new ArrayList<>();Set<Long> unique=new HashSet<>();
        try{for(int i=0;i<500;i++)calls.add(pool.submit(()->ids.nextId("pet:qa")));for(Future<Long> call:calls)unique.add(call.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        assertEquals(500,unique.size());assertTrue(unique.stream().allMatch(id->id>0));
    }
}
