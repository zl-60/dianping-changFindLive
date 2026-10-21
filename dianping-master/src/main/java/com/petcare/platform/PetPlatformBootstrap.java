package com.petcare.platform;

import com.petcare.*;
import org.springframework.boot.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Component @Profile("platform") @Order(20)
public class PetPlatformBootstrap implements ApplicationRunner {
    private final PetData db;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final PetCouponService coupons;
    private final boolean demo;
    private final TransactionTemplate tx;
    public PetPlatformBootstrap(PetData db,Clock clock,StringRedisTemplate redis,PetCouponService coupons,
        @Value("${pet.seed-demo:true}") boolean demo,org.springframework.transaction.PlatformTransactionManager manager) {
        this.db=db; this.clock=clock; this.redis=redis; this.coupons=coupons; this.demo=demo; this.tx=new TransactionTemplate(manager);
    }
    public void run(ApplicationArguments arguments) {
        tx.execute(status->{ db.lock(); seed(); return null; });
        for(Map<String,Object> place:db.rows("SELECT id,longitude,latitude FROM pet_place WHERE active=1"))
            redis.opsForGeo().add(PetKeys.GEO,new Point(((Number)place.get("longitude")).doubleValue(),((Number)place.get("latitude")).doubleValue()),place.get("id").toString());
        for(Map<String,Object> coupon:db.rows("SELECT id FROM pet_coupon WHERE published=1")) coupons.publish(((Number)coupon.get("id")).longValue());
    }
    private void seed() {
        if(db.count("SELECT COUNT(*) FROM pet_place WHERE id=1")==0) {
            Map<String,Object> store=db.one("SELECT * FROM pet_store WHERE id=1");
            db.update("INSERT INTO pet_place VALUES(1,?,?,?,?,120.155070,30.274084,1,1)",store.get("name"),store.get("address"),store.get("description"),"洗护美容");
        }
        if(!demo) return;
        if(db.count("SELECT COUNT(*) FROM pet_place WHERE id=2")==0)
            db.update("INSERT INTO pet_place VALUES(2,'爪印宠物生活馆','杭州 · 湖滨路 16 号（演示）','洗护、用品与宠友交流。','宠物生活',120.160000,30.278000,0,1)");
        if(db.count("SELECT COUNT(*) FROM pet_place WHERE id=3")==0)
            db.update("INSERT INTO pet_place VALUES(3,'喵汪护理小站','杭州 · 文新路 8 号（演示）','猫犬日常护理与洗护。','基础护理',120.145000,30.280000,0,1)");
        LocalDateTime now=LocalDateTime.now(clock);
        for(int i=1;i<=3;i++) {
            String phone="1380000000"+i;
            if(db.count("SELECT COUNT(*) FROM pet_member WHERE phone=?",phone)==0)
                db.update("INSERT INTO pet_member(phone,nickname,created_at) VALUES(?,?,?)",phone,Arrays.asList("豆豆的家长","团子的日常","养宠小记").get(i-1),Timestamp.valueOf(now));
        }
        if(db.count("SELECT COUNT(*) FROM pet_coupon")==0)
            db.update("INSERT INTO pet_coupon(place_id,title,discount_cents,min_spend_cents,stock,starts_at,ends_at,valid_until,published) VALUES(1,'洗护满 69 减 20 元券',2000,6900,30,?,?,?,1)",Timestamp.valueOf(now.minusHours(1)),Timestamp.valueOf(now.plusDays(7)),Timestamp.valueOf(now.plusDays(30)));
        if(db.count("SELECT COUNT(*) FROM pet_post")==0) {
            long author=((Number)db.one("SELECT id FROM pet_member WHERE phone='13800000002'").get("id")).longValue();
            db.update("INSERT INTO pet_post VALUES(90001,?,1,'带团子来洗澡的一天','提前选好时间，到店就能安排洗护。团子吹完毛蓬松得像一朵云！',?)",author,Timestamp.valueOf(now.minusMinutes(10)));
            db.update("INSERT INTO pet_social_task VALUES(90001,90001,?,?,0)",author,Timestamp.valueOf(now.minusMinutes(10)));
            long other=((Number)db.one("SELECT id FROM pet_member WHERE phone='13800000003'").get("id")).longValue();
            db.update("INSERT INTO pet_post VALUES(90002,?,NULL,'换季梳毛小记录','最近每天给毛孩子梳一会儿毛，把护理变成固定的陪伴时间。你们有什么日常护理习惯？',?)",other,Timestamp.valueOf(now.minusMinutes(5)));
            db.update("INSERT INTO pet_social_task VALUES(90002,90002,?,?,0)",other,Timestamp.valueOf(now.minusMinutes(5)));
        }
    }
}
