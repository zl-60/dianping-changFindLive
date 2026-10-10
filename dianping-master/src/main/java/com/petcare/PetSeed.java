package com.petcare;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Component
public class PetSeed implements ApplicationRunner {
    private final PetData db; private final Clock clock; private final boolean demo;
    public PetSeed(PetData db,Clock clock,@Value("${pet.seed-demo:true}") boolean demo) { this.db=db; this.clock=clock; this.demo=demo; }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if(db.count("SELECT COUNT(*) FROM pet_lock WHERE id=1")==0) {
            try { db.update("INSERT INTO pet_lock(id) VALUES(1)"); }
            catch(org.springframework.dao.DuplicateKeyException alreadyCreated) { /* Another instance initialized it. */ }
        }
        db.lock();
        if(db.count("SELECT COUNT(*) FROM pet_store")==0)
            db.update("INSERT INTO pet_store VALUES(1,?,?,?,?,9,18)","宠伴洗护","杭州 · 春日路 28 号（演示门店）","0571-00000000","把每一次洗护，变成被温柔照顾的时光。独立猫咪洗护区，细心照顾每一位毛孩子。");
        if(!demo) return;
        boolean first=db.count("SELECT COUNT(*) FROM pet_spec")==0;
        if(first) {
            add("清爽洗澡","DOG_BATH","DOG","小型犬 · 0–10kg",0,10,6900,1,"温和清洁、吹干梳毛、耳部与爪部基础清洁。");
            add("清爽洗澡","DOG_BATH","DOG","中型犬 · 10–20kg",10,20,9900,1,"适合中型犬的完整洗护，洗后蓬松清爽。");
            add("清爽洗澡","DOG_BATH","DOG","大型犬 · 20–200kg",20,200,13900,2,"为大体型毛孩子预留充足的清洁与吹干时间。");
            add("猫咪轻柔洗护","CAT_BATH","CAT","猫咪 · 0–200kg",0,200,9900,1,"独立区域，轻柔清洁与梳理，让洗护更安心。");
            add("精致造型","DOG_GROOM","DOG","小型犬 · 0–10kg",0,10,15900,2,"包含洗澡、吹干与基础造型，具体造型到店沟通。");
            add("精致造型","DOG_GROOM","DOG","中大型犬 · 10–200kg",10,200,22900,2,"细致修剪与整体造型，为毛孩子打造清爽新模样。");
            add("基础护理","CARE","DOG","狗狗 · 0–200kg",0,200,3900,1,"指甲修整、梳毛与耳部基础清洁。");
            add("基础护理","CARE","CAT","猫咪 · 0–200kg",0,200,3900,1,"日常梳理与基础清洁，细心照顾小细节。");
        }
        if(db.count("SELECT COUNT(*) FROM pet_staff")==0) {
            db.update("INSERT INTO pet_staff(name,skills,active) VALUES('小林','DOG_BATH,DOG_GROOM,CARE',1)");
            db.update("INSERT INTO pet_staff(name,skills,active) VALUES('阿晴','DOG_BATH,CAT_BATH,CARE',1)");
            db.update("INSERT INTO pet_staff(name,skills,active) VALUES('小周','DOG_BATH,DOG_GROOM,CAT_BATH,CARE',1)");
        }
        LocalDate today=LocalDate.now(clock);
        if(first) for(Map<String,Object> person:db.rows("SELECT id FROM pet_staff WHERE active=1"))
            for(int day=0;day<7;day++) {
                java.sql.Date date=java.sql.Date.valueOf(today.plusDays(day));
                if(db.count("SELECT COUNT(*) FROM pet_shift WHERE staff_id=? AND work_date=?",person.get("id"),date)==0)
                    db.update("INSERT INTO pet_shift(staff_id,work_date,start_hour,end_hour) VALUES(?,?,9,18)",person.get("id"),date);
            }
        if(first) {
            example("demo-completed","COMPLETED",today.minusDays(1).atTime(10,0),1,0,null);
            db.update("INSERT INTO pet_review VALUES('demo-completed',5,?,?)","工作人员很耐心，洗完蓬松又清爽，下次还会带豆豆来！",Timestamp.valueOf(today.minusDays(1).atTime(12,0)));
            example("demo-cancelled","CANCELLED",today.minusDays(1).atTime(14,0),1,1,"店主拒单，已全额模拟退款");
            example("demo-payment-timeout","UNPAID",today.plusDays(1).atTime(10,0),0,0,null);
            example("demo-confirm-timeout","PENDING",today.plusDays(1).atTime(11,0),1,0,null);
        }
    }
    private void add(String title,String category,String species,String size,int min,int max,int price,int hours,String description) {
        db.update("INSERT INTO pet_spec(title,category,species,size_label,min_weight,max_weight,price_cents,duration_hours,description,active) VALUES(?,?,?,?,?,?,?,?,?,1)",title,category,species,size,min,max,price,hours,description);
    }
    private void example(String id,String status,LocalDateTime start,int paid,int refunded,String reason) {
        Map<String,Object> spec=db.one("SELECT id FROM pet_spec WHERE category='DOG_BATH' ORDER BY id LIMIT 1");
        Map<String,Object> staff=db.one("SELECT id,name FROM pet_staff ORDER BY id LIMIT 1");
        LocalDateTime now=LocalDateTime.now(clock);
        boolean expired=Arrays.asList("UNPAID","PENDING").contains(status);
        db.update("INSERT INTO pet_order(id,user_phone,request_key,spec_id,service_title,size_label,species,price_cents,duration_hours,pet_name,breed,weight,notes,contact_name,contact_phone,staff_id,staff_name,starts_at,ends_at,status,created_at,deadline,booking_code,paid,refunded,cancel_reason,completed_at,paid_at,confirmed_at,arrived_at,started_at,cancelled_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,"13800000001",id,spec.get("id"),"清爽洗澡","小型犬 · 0–10kg","DOG",6900,1,"豆豆","比熊",5,"演示订单","演示顾客","13800000001",staff.get("id"),staff.get("name"),Timestamp.valueOf(start),Timestamp.valueOf(start.plusHours(1)),status,Timestamp.valueOf(now.minusHours(1)),expired?Timestamp.valueOf(now.minusMinutes(1)):null,id.replace("demo-","").substring(0,Math.min(10,id.replace("demo-","").length())).toUpperCase(Locale.ROOT),paid,refunded,reason,
            "COMPLETED".equals(status)?Timestamp.valueOf(start.plusHours(1)):null,paid==1?Timestamp.valueOf(now.minusMinutes(50)):null,"COMPLETED".equals(status)?Timestamp.valueOf(start.minusHours(2)):null,"COMPLETED".equals(status)?Timestamp.valueOf(start):null,"COMPLETED".equals(status)?Timestamp.valueOf(start):null,"CANCELLED".equals(status)?Timestamp.valueOf(now.minusMinutes(30)):null);
        if(expired) db.update("INSERT INTO pet_slot(staff_id,slot_start,order_id) VALUES(?,?,?)",staff.get("id"),Timestamp.valueOf(start),id);
    }
}
