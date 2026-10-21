package com.petcare;

import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Coupon redemption shares the booking transaction and database resource locks. */
@Service
public class PetCouponUse {
    private final PetData db;
    private final Clock clock;
    public PetCouponUse(PetData db,Clock clock) { this.db=db; this.clock=clock; }
    public String requested(Map<String,Object> input) {
        Object raw=input.get("couponOrderId");
        if(raw==null||raw.toString().isEmpty()) return null;
        try { long id=Long.parseLong(raw.toString()); if(id<=0) throw new IllegalArgumentException(); return String.valueOf(id); }
        catch(RuntimeException error) { throw new PetException("优惠券编号不正确"); }
    }
    public void sameRequest(String orderId,Map<String,Object> input) {
        List<Map<String,Object>> rows=db.rows("SELECT coupon_order_id FROM pet_coupon_history WHERE order_id=?",orderId);
        String previous=rows.isEmpty()?null:rows.get(0).get("couponOrderId").toString();
        if(!Objects.equals(previous,requested(input))) throw new PetException(409,"重复请求使用的优惠券已改变");
    }
    public Map<String,Object> check(String phone,Map<String,Object> input,int price) {
        String id=requested(input); if(id==null) return null;
        List<Map<String,Object>> rows=db.rows("SELECT o.*,c.discount_cents,c.min_spend_cents,c.valid_until,c.place_id,m.phone FROM pet_coupon_order o JOIN pet_coupon c ON c.id=o.coupon_id JOIN pet_member m ON m.id=o.user_id WHERE o.id=? FOR UPDATE",Long.parseLong(id));
        if(rows.isEmpty()) throw new PetException("优惠券不存在");
        Map<String,Object> row=rows.get(0);
        if(!phone.equals(row.get("phone"))) throw new PetException(403,"只能使用自己的优惠券");
        if(!"SUCCESS".equals(row.get("status"))||!LocalDateTime.parse(row.get("validUntil").toString()).isAfter(LocalDateTime.now(clock))) throw new PetException("优惠券已使用或已过期");
        if(((Number)row.get("placeId")).intValue()!=1||price<((Number)row.get("minSpendCents")).intValue()) throw new PetException("此预约不满足优惠券使用条件");
        row.put("discount",Math.min(price,((Number)row.get("discountCents")).intValue())); return row;
    }
    public void use(String orderId,Map<String,Object> coupon,int original) {
        if(coupon==null) return;
        int discount=((Number)coupon.get("discount")).intValue();
        if(db.update("UPDATE pet_coupon_order SET status='USED',redeemed_order_id=? WHERE id=? AND status='SUCCESS'",orderId,coupon.get("id"))!=1)
            throw new IllegalStateException("优惠券状态发生冲突");
        db.update("INSERT INTO pet_coupon_use(order_id,coupon_order_id,discount_cents,original_cents) VALUES(?,?,?,?)",orderId,coupon.get("id"),discount,original);
        db.update("INSERT INTO pet_coupon_history(order_id,coupon_order_id,discount_cents,original_cents) VALUES(?,?,?,?)",orderId,coupon.get("id"),discount,original);
        db.update("UPDATE pet_order SET price_cents=? WHERE id=?",original-discount,orderId);
    }
    public void release(String orderId) {
        db.update("UPDATE pet_coupon_order SET status='SUCCESS',redeemed_order_id=NULL WHERE redeemed_order_id=? AND status='USED'",orderId);
        db.update("DELETE FROM pet_coupon_use WHERE order_id=?",orderId);
    }
}
