package com.petcare;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.petcare.PetData.*;

/** All writes share a database row lock: suitable for one store and multiple server processes. */
@Service
@Transactional(noRollbackFor = PetException.class)
public class PetBookingService {
    private final PetData db;
    private final Clock clock;
    private final PetCouponUse coupons;
    private final org.springframework.context.ApplicationEventPublisher events;
    public PetBookingService(PetData db,Clock clock,PetCouponUse coupons,org.springframework.context.ApplicationEventPublisher events) {
        this.db=db; this.clock=clock; this.coupons=coupons; this.events=events;
    }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private int n(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
    private LocalDateTime time(Map<String,Object> row,String key) { return LocalDateTime.parse((String)row.get(key)); }
    public Map<String,Object> store() {
        Map<String,Object> store=db.one("SELECT * FROM pet_store WHERE id=1");
        store.put("serverNow",now().toString()); return store;
    }
    public List<Map<String,Object>> specs(boolean owner) {
        return db.rows("SELECT * FROM pet_spec"+(owner?"":" WHERE active=1")+" ORDER BY category,id");
    }
    public List<Map<String,Object>> staff() { return db.rows("SELECT * FROM pet_staff ORDER BY id"); }
    private Map<String,Object> spec(long id) {
        Map<String,Object> spec=db.one("SELECT * FROM pet_spec WHERE id=?",id);
        if(n(spec,"active")!=1) throw new PetException("该服务已经下架");
        return spec;
    }
    private void validateTime(LocalDateTime start,int hours) {
        LocalDate today=now().toLocalDate(); Map<String,Object> store=store();
        if(start.getMinute()!=0 || start.getSecond()!=0 || start.getNano()!=0 ||
            start.toLocalDate().isBefore(today) || start.toLocalDate().isAfter(today.plusDays(6)) ||
            start.isBefore(now().plusHours(2))) throw new PetException("请选择未来七天内、至少提前两小时的整点时间");
        if(start.getHour()<n(store,"openHour") || !start.plusHours(hours).toLocalDate().equals(start.toLocalDate()) ||
            start.plusHours(hours).getHour()>n(store,"closeHour")) throw new PetException("服务时间超出营业时间");
    }
    private List<Map<String,Object>> availableStaff(Map<String,Object> spec,LocalDateTime start,Long excludedOrderStaff) {
        int hours=n(spec,"durationHours");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> staff:db.rows("SELECT * FROM pet_staff WHERE active=1 ORDER BY id")) {
            long id=((Number)staff.get("id")).longValue();
            if(excludedOrderStaff!=null && id==excludedOrderStaff) continue;
            if(!Arrays.asList(((String)staff.get("skills")).split(",")).contains(spec.get("category"))) continue;
            if(db.count("SELECT COUNT(*) FROM pet_shift WHERE staff_id=? AND work_date=? AND start_hour<=? AND end_hour>=?",
                id,java.sql.Date.valueOf(start.toLocalDate()),start.getHour(),start.getHour()+hours)==0) continue;
            if(db.count("SELECT COUNT(*) FROM pet_slot WHERE staff_id=? AND slot_start>=? AND slot_start<?",id,
                Timestamp.valueOf(start),Timestamp.valueOf(start.plusHours(hours)))==0) result.add(staff);
        }
        return result;
    }
    public List<Map<String,Object>> availability(long specId,String date) {
        db.lock(); expireLocked(); Map<String,Object> spec=spec(specId);
        LocalDate day=parseDate(date); List<Map<String,Object>> slots=new ArrayList<>();
        Map<String,Object> store=store();
        for(int hour=n(store,"openHour");hour+n(spec,"durationHours")<=n(store,"closeHour");hour++) {
            LocalDateTime start=day.atTime(hour,0);
            try { validateTime(start,n(spec,"durationHours")); }
            catch(PetException error) { continue; }
            int available=availableStaff(spec,start,null).size();
            slots.add(map("startsAt",start.toString(),"endsAt",start.plusHours(n(spec,"durationHours")).toString(),"available",available));
        }
        return slots;
    }
    public Map<String,Object> create(String user,Map<String,Object> input) {
        String requestKey=text(input,"requestKey",64); long specId=number(input,"specId",1,Integer.MAX_VALUE);
        LocalDateTime start=parseTime(text(input,"startsAt",30));
        String petName=text(input,"petName",40),species=text(input,"species",10),breed=text(input,"breed",60);
        String contact=text(input,"contactName",40),phone=PetAuth.phone(input.get("contactPhone"));
        String notes=optional(input,"notes",500); BigDecimal weight;
        try { weight=new BigDecimal(String.valueOf(input.get("weight"))); if(weight.signum()<=0 || weight.scale()>2 || weight.compareTo(new BigDecimal("200"))>0) throw new IllegalArgumentException(); }
        catch(RuntimeException error) { throw new PetException("体重应为大于零、不超过两百的数，最多两位小数"); }
        db.lock(); expireLocked();
        List<Map<String,Object>> existing=db.rows("SELECT * FROM pet_order WHERE user_phone=? AND request_key=?",user,requestKey);
        if(!existing.isEmpty()) {
            Map<String,Object> previous=existing.get(0);
            if(((Number)previous.get("specId")).longValue()!=specId || !time(previous,"startsAt").equals(start) ||
                !petName.equals(previous.get("petName")) || !species.equals(previous.get("species")) ||
                !breed.equals(previous.get("breed")) || weight.compareTo((BigDecimal)previous.get("weight"))!=0 ||
                !notes.equals(previous.get("notes")) || !contact.equals(previous.get("contactName")) || !phone.equals(previous.get("contactPhone")))
                throw new PetException(409,"重复请求的内容已改变，请返回重新预约");
            coupons.sameRequest((String)previous.get("id"),input);
            return decorate(previous);
        }
        Map<String,Object> spec=spec(specId); int hours=n(spec,"durationHours");
        if(!species.equals(spec.get("species")) || weight.compareTo((BigDecimal)spec.get("minWeight"))<=0 || weight.compareTo((BigDecimal)spec.get("maxWeight"))>0)
            throw new PetException("宠物种类或体重不符合所选规格，请返回服务页面调整规格");
        validateTime(start,hours);
        List<Map<String,Object>> candidates=availableStaff(spec,start,null);
        if(candidates.isEmpty()) throw new PetException(409,"这个时间已经约满，请返回选择其他时间");
        Map<String,Object> assigned=candidates.get(0);
        Map<String,Object> coupon=coupons.check(user,input,n(spec,"priceCents"));
        String id=UUID.randomUUID().toString().replace("-","");
        String code=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(Locale.ROOT);
        db.update("INSERT INTO pet_order(id,user_phone,request_key,spec_id,service_title,size_label,species,price_cents,duration_hours,pet_name,breed,weight,notes,contact_name,contact_phone,staff_id,staff_name,starts_at,ends_at,status,created_at,deadline,booking_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,user,requestKey,specId,spec.get("title"),spec.get("sizeLabel"),species,spec.get("priceCents"),hours,
            petName,breed,weight,notes,contact,phone,assigned.get("id"),assigned.get("name"),Timestamp.valueOf(start),
            Timestamp.valueOf(start.plusHours(hours)),"UNPAID",Timestamp.valueOf(now()),Timestamp.valueOf(now().plusMinutes(10)),code);
        coupons.use(id,coupon,n(spec,"priceCents"));
        occupy(id,((Number)assigned.get("id")).longValue(),start,hours);
        return order(user,id,false);
    }
    private void occupy(String id,long staffId,LocalDateTime start,int hours) {
        for(int i=0;i<hours;i++) db.update("INSERT INTO pet_slot(staff_id,slot_start,order_id) VALUES(?,?,?)",staffId,Timestamp.valueOf(start.plusHours(i)),id);
    }
    public List<Map<String,Object>> orders(String user,boolean owner) {
        db.lock(); expireLocked();
        List<Map<String,Object>> result=owner?db.rows("SELECT * FROM pet_order ORDER BY created_at DESC"):db.rows("SELECT * FROM pet_order WHERE user_phone=? ORDER BY created_at DESC",user);
        result.forEach(this::decorate); return result;
    }
    public Map<String,Object> order(String user,String id,boolean owner) {
        Map<String,Object> order=db.one("SELECT * FROM pet_order WHERE id=?",id);
        if(!owner && !user.equals(order.get("userPhone"))) throw new PetException(403,"只能查看自己的订单");
        return decorate(order);
    }
    private Map<String,Object> decorate(Map<String,Object> order) {
        List<Map<String,Object>> discount=db.rows("SELECT coupon_order_id,discount_cents,original_cents FROM pet_coupon_history WHERE order_id=?",order.get("id"));
        order.put("coupon",discount.isEmpty()?null:discount.get(0));
        List<Map<String,Object>> review=db.rows("SELECT stars,content,created_at FROM pet_review WHERE order_id=?",order.get("id"));
        order.put("review",review.isEmpty()?null:review.get(0));
        order.put("serverNow",now().toString());
        order.put("canCancel","UNPAID".equals(order.get("status")) ||
            (Arrays.asList("PENDING","CONFIRMED").contains(order.get("status")) && !now().isAfter(time(order,"startsAt").minusHours(2))));
        return order;
    }
    public Map<String,Object> pay(String user,String id) {
        db.lock(); expireLocked(); Map<String,Object> order=order(user,id,false);
        if(n(order,"paid")==1 && !"CANCELLED".equals(order.get("status"))) return order;
        if(!"UNPAID".equals(order.get("status"))) throw new PetException("订单已取消，无法支付");
        db.update("UPDATE pet_order SET status='PENDING',paid=1,paid_at=?,deadline=? WHERE id=?",Timestamp.valueOf(now()),Timestamp.valueOf(now().plusMinutes(30)),id);
        return order(user,id,false);
    }
    public Map<String,Object> cancel(String user,String id) {
        db.lock(); expireLocked(); Map<String,Object> order=order(user,id,false);
        if("CANCELLED".equals(order.get("status"))) return order;
        if(!Boolean.TRUE.equals(order.get("canCancel"))) throw new PetException("已超过自行取消时间，请联系店主处理");
        cancelLocked(id,"顾客取消"); return order(user,id,false);
    }
    private void cancelLocked(String id,String reason) {
        db.update("UPDATE pet_order SET status='CANCELLED',refunded=paid,deadline=NULL,cancel_reason=?,cancelled_at=? WHERE id=?",reason,Timestamp.valueOf(now()),id);
        db.update("DELETE FROM pet_slot WHERE order_id=?",id);
        coupons.release(id);
    }
    public void expire() { db.lock(); expireLocked(); }
    private void expireLocked() {
        for(Map<String,Object> order:db.rows("SELECT id,status FROM pet_order WHERE status IN ('UNPAID','PENDING') AND deadline<=?",Timestamp.valueOf(now())))
            cancelLocked((String)order.get("id"),"UNPAID".equals(order.get("status"))?"超过十分钟未支付":"店主超过三十分钟未确认");
        db.update("DELETE FROM pet_session WHERE expires_at<=?",Timestamp.valueOf(now()));
        db.update("DELETE FROM pet_login_code WHERE expires_at<=?",Timestamp.valueOf(now()));
    }
    public Map<String,Object> action(String id,String action,Map<String,Object> input) {
        db.lock(); expireLocked(); Map<String,Object> order=order(null,id,true);
        String status=(String)order.get("status");
        switch(action) {
            case "confirm":
                if("CONFIRMED".equals(status)) return order;
                requireState(status,"PENDING");
                db.update("UPDATE pet_order SET status='CONFIRMED',confirmed_at=?,deadline=NULL WHERE id=?",Timestamp.valueOf(now()),id); break;
            case "reject": case "cancel": case "noshow":
                if("CANCELLED".equals(status)) return order;
                if(!Arrays.asList("UNPAID","PENDING","CONFIRMED","ARRIVED").contains(status)) throw new PetException("已开始或已完成的服务不能取消");
                if("reject".equals(action)) requireState(status,"PENDING");
                if("noshow".equals(action) && (now().isBefore(time(order,"startsAt")) || !"CONFIRMED".equals(status))) throw new PetException("只可处理已到预约时间且未到店的订单");
                cancelLocked(id,optional(input,"reason",200).isEmpty()?"店主取消并全额模拟退款":optional(input,"reason",200)); break;
            case "arrive":
                String code=text(input,"code",12).toUpperCase(Locale.ROOT);
                if(!code.equals(order.get("bookingCode"))) throw new PetException("预约码不正确");
                if("ARRIVED".equals(status)) return order;
                requireState(status,"CONFIRMED");
                if(!now().toLocalDate().equals(time(order,"startsAt").toLocalDate())) throw new PetException("请在预约当天办理到店核验");
                db.update("UPDATE pet_order SET status='ARRIVED',arrived_at=? WHERE id=?",Timestamp.valueOf(now()),id); break;
            case "start":
                if("IN_SERVICE".equals(status)) return order;
                requireState(status,"ARRIVED");
                if(now().isBefore(time(order,"startsAt")) || !now().isBefore(time(order,"endsAt"))) throw new PetException("请在原预约时段内开始服务；迟到不会延长预约时段");
                if(db.count("SELECT COUNT(*) FROM pet_order WHERE staff_id=? AND status='IN_SERVICE'",order.get("staffId"))>0) throw new PetException("该美容师还有未完成的服务，请先处理");
                db.update("UPDATE pet_order SET status='IN_SERVICE',started_at=? WHERE id=?",Timestamp.valueOf(now()),id); break;
            case "finish":
                if("COMPLETED".equals(status)) return order;
                requireState(status,"IN_SERVICE");
                db.update("UPDATE pet_order SET status='COMPLETED',completed_at=? WHERE id=?",Timestamp.valueOf(now()),id); break;
            case "reassign":
                if(!Arrays.asList("UNPAID","PENDING","CONFIRMED","ARRIVED").contains(status)) throw new PetException("此订单不能更换美容师");
                int staffId=number(input,"staffId",1,Integer.MAX_VALUE);
                Map<String,Object> snapshot=map("durationHours",order.get("durationHours"),"category",db.one("SELECT category FROM pet_spec WHERE id=?",order.get("specId")).get("category"));
                List<Map<String,Object>> candidates=availableStaff(snapshot,time(order,"startsAt"),((Number)order.get("staffId")).longValue());
                Map<String,Object> replacement=candidates.stream().filter(person->((Number)person.get("id")).intValue()==staffId).findFirst().orElseThrow(()->new PetException("该美容师无法承接原预约时段"));
                db.update("DELETE FROM pet_slot WHERE order_id=?",id);
                occupy(id,staffId,time(order,"startsAt"),n(order,"durationHours"));
                db.update("UPDATE pet_order SET staff_id=?,staff_name=? WHERE id=?",staffId,replacement.get("name"),id); break;
            default: throw new PetException("未知订单操作");
        }
        return order(null,id,true);
    }
    private void requireState(String status,String expected) {
        if(!expected.equals(status)) throw new PetException("当前订单状态不允许此操作");
    }
    public void review(String user,String id,Map<String,Object> input) {
        db.lock(); Map<String,Object> order=order(user,id,false);
        requireState((String)order.get("status"),"COMPLETED");
        if(order.get("review")!=null) throw new PetException("此订单已经评价过");
        db.update("INSERT INTO pet_review(order_id,stars,content,created_at) VALUES(?,?,?,?)",id,number(input,"stars",1,5),text(input,"content",500),Timestamp.valueOf(now()));
    }
    public List<Map<String,Object>> reviews() {
        return db.rows("SELECT r.stars,r.content,r.created_at,o.service_title FROM pet_review r JOIN pet_order o ON o.id=r.order_id ORDER BY r.created_at DESC LIMIT 30");
    }
    public Map<String,Object> stats() {
        db.lock(); expireLocked();
        Map<String,Object> totals=db.one("SELECT COUNT(*) AS total,COALESCE(SUM(CASE WHEN paid=1 THEN price_cents ELSE 0 END),0) AS collected,COALESCE(SUM(CASE WHEN refunded=1 THEN price_cents ELSE 0 END),0) AS refunded FROM pet_order");
        totals.put("states",db.rows("SELECT status,COUNT(*) AS count FROM pet_order GROUP BY status"));
        totals.put("today",db.count("SELECT COUNT(*) FROM pet_order WHERE starts_at>=? AND starts_at<? AND status<>'CANCELLED'",Timestamp.valueOf(now().toLocalDate().atStartOfDay()),Timestamp.valueOf(now().toLocalDate().plusDays(1).atStartOfDay())));
        return totals;
    }
    public void saveSpec(Long id,Map<String,Object> input) {
        db.lock();
        String species=text(input,"species",10),category=text(input,"category",30);
        if(!Arrays.asList("DOG","CAT").contains(species) || !Arrays.asList("DOG_BATH","CAT_BATH","DOG_GROOM","CARE").contains(category) ||
            (category.startsWith("DOG_")&&!species.equals("DOG")) || (category.equals("CAT_BATH")&&!species.equals("CAT"))) throw new PetException("服务类别与宠物种类不匹配");
        BigDecimal min=decimal(input,"minWeight"),max=decimal(input,"maxWeight");
        if(min.signum()<0 || max.compareTo(min)<=0 || max.compareTo(new BigDecimal("200"))>0) throw new PetException("体重范围不合法");
        String title=text(input,"title",80),size=text(input,"sizeLabel",30),description=text(input,"description",500);
        int price=number(input,"priceCents",1,1000000),duration=number(input,"durationHours",1,8),active=number(input,"active",0,1);
        if(id==null) db.update("INSERT INTO pet_spec(title,category,species,size_label,min_weight,max_weight,price_cents,duration_hours,description,active) VALUES(?,?,?,?,?,?,?,?,?,?)",title,category,species,size,min,max,price,duration,description,active);
        else {
            Map<String,Object> old=db.one("SELECT category FROM pet_spec WHERE id=?",id);
            if(!category.equals(old.get("category")) && db.count("SELECT COUNT(*) FROM pet_order WHERE spec_id=?",id)>0) throw new PetException("已有订单的规格不能更改服务类别，请新增规格");
            db.update("UPDATE pet_spec SET title=?,category=?,species=?,size_label=?,min_weight=?,max_weight=?,price_cents=?,duration_hours=?,description=?,active=? WHERE id=?",title,category,species,size,min,max,price,duration,description,active,id);
            events.publishEvent(new PetCatalogChanged(id));
        }
    }
    public void saveStaff(Long id,Map<String,Object> input) {
        db.lock(); expireLocked(); String name=text(input,"name",40),skills=text(input,"skills",200); int active=number(input,"active",0,1);
        for(String skill:skills.split(",")) if(!Arrays.asList("DOG_BATH","CAT_BATH","DOG_GROOM","CARE").contains(skill)) throw new PetException("服务能力不合法");
        if(id!=null) {
            db.one("SELECT id FROM pet_staff WHERE id=?",id);
            for(Map<String,Object> order:db.rows("SELECT o.id,s.category FROM pet_order o JOIN pet_spec s ON s.id=o.spec_id WHERE o.staff_id=? AND o.status NOT IN ('CANCELLED','COMPLETED')",id))
                if(active==0 || !Arrays.asList(skills.split(",")).contains(order.get("category"))) throw new PetException("此美容师存在冲突订单，请先换人或取消订单");
            db.update("UPDATE pet_staff SET name=?,skills=?,active=? WHERE id=?",name,skills,active,id);
        } else db.update("INSERT INTO pet_staff(name,skills,active) VALUES(?,?,?)",name,skills,active);
    }
    public List<Map<String,Object>> shifts() { return db.rows("SELECT sh.*,s.name AS staff_name FROM pet_shift sh JOIN pet_staff s ON s.id=sh.staff_id ORDER BY work_date,staff_id"); }
    public void saveShift(Map<String,Object> input,boolean delete) {
        db.lock(); expireLocked(); int staffId=number(input,"staffId",1,Integer.MAX_VALUE); LocalDate date=parseDate(text(input,"workDate",10));
        db.one("SELECT id FROM pet_staff WHERE id=?",staffId);
        int start=delete?0:number(input,"startHour",0,23),end=delete?0:number(input,"endHour",1,24);
        Map<String,Object> store=store();
        if(!delete && (start>=end || start<n(store,"openHour") || end>n(store,"closeHour"))) throw new PetException("排班必须在营业时间内");
        List<Map<String,Object>> orders=db.rows("SELECT starts_at,ends_at FROM pet_order WHERE staff_id=? AND starts_at>=? AND starts_at<? AND status NOT IN ('CANCELLED','COMPLETED')",staffId,Timestamp.valueOf(date.atStartOfDay()),Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
        for(Map<String,Object> order:orders) if(delete || time(order,"startsAt").getHour()<start || time(order,"endsAt").getHour()>end) throw new PetException("排班与已有预约冲突，请先换人或取消冲突订单");
        db.update("DELETE FROM pet_shift WHERE staff_id=? AND work_date=?",staffId,java.sql.Date.valueOf(date));
        if(!delete) db.update("INSERT INTO pet_shift(staff_id,work_date,start_hour,end_hour) VALUES(?,?,?,?)",staffId,java.sql.Date.valueOf(date),start,end);
    }
    public void saveStore(Map<String,Object> input) {
        db.lock(); expireLocked(); int start=number(input,"openHour",0,23),end=number(input,"closeHour",1,23);
        if(start>=end) throw new PetException("营业时间不合法");
        if(db.count("SELECT COUNT(*) FROM pet_order WHERE status NOT IN ('CANCELLED','COMPLETED')")>0) {
            for(Map<String,Object> order:db.rows("SELECT starts_at,ends_at FROM pet_order WHERE status NOT IN ('CANCELLED','COMPLETED')"))
                if(time(order,"startsAt").getHour()<start || time(order,"endsAt").getHour()>end) throw new PetException("营业时间与已有预约冲突，请先处理订单");
        }
        db.update("UPDATE pet_store SET name=?,address=?,phone=?,description=?,open_hour=?,close_hour=? WHERE id=1",text(input,"name",80),text(input,"address",200),text(input,"phone",30),text(input,"description",500),start,end);
        events.publishEvent(new PetCatalogChanged(null));
    }
    private BigDecimal decimal(Map<String,Object> input,String key) {
        try { BigDecimal value=new BigDecimal(String.valueOf(input.get(key))); if(value.scale()>2) throw new IllegalArgumentException(); return value; }
        catch(RuntimeException error) { throw new PetException(key+" 数值不合法"); }
    }
    private String optional(Map<String,Object> input,String key,int max) {
        Object value=input.get(key); if(value==null) return "";
        if(!(value instanceof String) || ((String)value).length()>max) throw new PetException(key+" 内容过长");
        return ((String)value).trim();
    }
    private LocalDate parseDate(String value) { try {return LocalDate.parse(value);}catch(RuntimeException error){throw new PetException("日期格式不正确");} }
    private LocalDateTime parseTime(String value) { try {return LocalDateTime.parse(value);}catch(RuntimeException error){throw new PetException("时间格式不正确");} }
}
