package com.petcare;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.petcare.PetData.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@SpringBootTest(classes={PetApplication.class,PetBookingIntegrationTest.TimeConfig.class},properties={
    "spring.config.name=pet", "spring.profiles.active=demo",
    "spring.datasource.url=jdbc:h2:mem:pet-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
    "pet.expiry-enabled=false"
})
class PetBookingIntegrationTest {
    static class MutableClock extends Clock {
        volatile Instant value=Instant.parse("2026-10-10T00:00:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Shanghai");}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return value;}
        void advance(Duration duration){value=value.plus(duration);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class TimeConfig { @Bean @Primary MutableClock testClock(){return new MutableClock();} }
    @Autowired PetBookingService booking;
    @Autowired PetAuth auth;
    @Autowired PetData db;
    @Autowired MutableClock clock;
    @Autowired MockMvc http;
    long staffId,specId;
    @BeforeEach void reset() {
        clock.value=Instant.parse("2026-10-10T00:00:00Z");
        db.update("DELETE FROM pet_review");db.update("DELETE FROM pet_slot");db.update("DELETE FROM pet_order");
        db.update("DELETE FROM pet_session");db.update("DELETE FROM pet_login_code");
        db.update("UPDATE pet_store SET open_hour=9,close_hour=18 WHERE id=1");
        db.update("UPDATE pet_staff SET active=0");
        staffId=((Number)db.one("SELECT id FROM pet_staff ORDER BY id LIMIT 1").get("id")).longValue();
        specId=((Number)db.one("SELECT id FROM pet_spec WHERE category='DOG_BATH' ORDER BY id LIMIT 1").get("id")).longValue();
        db.update("UPDATE pet_staff SET active=1,skills='DOG_BATH,DOG_GROOM,CAT_BATH,CARE' WHERE id=?",staffId);
        db.update("UPDATE pet_spec SET title='清爽洗澡',price_cents=6900,duration_hours=1,active=1 WHERE id=?",specId);
        db.update("DELETE FROM pet_shift");
        for(Map<String,Object> person:db.rows("SELECT id FROM pet_staff"))for(int i=0;i<7;i++)
            db.update("INSERT INTO pet_shift(staff_id,work_date,start_hour,end_hour) VALUES(?,?,9,18)",person.get("id"),java.sql.Date.valueOf(LocalDate.now(clock).plusDays(i)));
    }
    Map<String,Object> input(String start) {
        return map("requestKey",UUID.randomUUID().toString(),"specId",specId,"startsAt",start,
            "species","DOG","petName","豆豆","breed","比熊","weight",5,"notes","怕吹风",
            "contactName","家长","contactPhone","13800000001");
    }
    String create() {return (String)booking.create("13800000001",input("2026-10-10T11:00")).get("id");}
    @Test void completeServiceAndReviewOnce() {
        String id=create();booking.pay("13800000001",id);booking.action(id,"confirm",map());
        Map<String,Object> order=booking.order("13800000001",id,false);
        clock.advance(Duration.ofHours(3));
        assertThrows(PetException.class,()->booking.action(id,"arrive",map("code","WRONG")));
        booking.action(id,"arrive",map("code",order.get("bookingCode")));
        booking.action(id,"start",map());booking.action(id,"finish",map());
        booking.review("13800000001",id,map("stars",5,"content","很满意"));
        assertThrows(PetException.class,()->booking.review("13800000001",id,map("stars",4,"content","再评价")));
        assertEquals("COMPLETED",booking.order("13800000001",id,false).get("status"));
        assertEquals(1,booking.reviews().size());
        assertFalse(booking.reviews().get(0).containsKey("userPhone"));
    }
    @Test void concurrentLastSlotHasExactlyOneWinner() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch ready=new CountDownLatch(8),start=new CountDownLatch(1);
        AtomicInteger successes=new AtomicInteger(),conflicts=new AtomicInteger();List<Future<?>> tasks=new ArrayList<>();
        try {
            for(int i=0;i<8;i++)tasks.add(pool.submit(()->{ready.countDown();try {start.await();booking.create("13800000001",input("2026-10-10T11:00"));successes.incrementAndGet();}catch(PetException error){assertEquals(409,error.status);conflicts.incrementAndGet();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();for(Future<?> task:tasks)task.get(30,TimeUnit.SECONDS);
            assertEquals(1,successes.get());assertEquals(7,conflicts.get());assertEquals(1,db.count("SELECT COUNT(*) FROM pet_slot"));
        } finally {pool.shutdownNow();}
    }
    @Test void twoHourServiceBlocksBothHoursButAllowsBoundary() {
        long large=((Number)db.one("SELECT id FROM pet_spec WHERE category='DOG_BATH' AND duration_hours=2 ORDER BY id LIMIT 1").get("id")).longValue();
        Map<String,Object> body=input("2026-10-10T11:00");body.put("specId",large);body.put("weight",25);
        booking.create("13800000001",body);
        assertThrows(PetException.class,()->booking.create("13800000001",input("2026-10-10T12:00")));
        booking.create("13800000001",input("2026-10-10T13:00"));assertEquals(3,db.count("SELECT COUNT(*) FROM pet_slot"));
    }
    @Test void paymentTimeoutReleasesSlotEvenWhenLatePaymentFails() {
        String id=create();clock.advance(Duration.ofMinutes(10));
        assertThrows(PetException.class,()->booking.pay("13800000001",id));
        assertEquals("CANCELLED",booking.order("13800000001",id,false).get("status"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM pet_slot"));
        assertEquals(0,((Number)booking.order("13800000001",id,false).get("refunded")).intValue());
        booking.create("13800000001",input("2026-10-10T11:00"));
    }
    @Test void confirmationTimeoutRefundsAndReleases() {
        String id=create();booking.pay("13800000001",id);clock.advance(Duration.ofMinutes(30));booking.expire();
        Map<String,Object> order=booking.order("13800000001",id,false);assertEquals("CANCELLED",order.get("status"));
        assertEquals(1,((Number)order.get("refunded")).intValue());assertEquals(0,db.count("SELECT COUNT(*) FROM pet_slot"));
    }
    @Test void paymentAndCreationRetriesAreIdempotent() {
        Map<String,Object> body=input("2026-10-10T11:00");String id=(String)booking.create("13800000001",body).get("id");
        assertEquals(id,booking.create("13800000001",body).get("id"));
        Object deadline=booking.pay("13800000001",id).get("deadline");clock.advance(Duration.ofMinutes(1));
        assertEquals(deadline,booking.pay("13800000001",id).get("deadline"));assertEquals(1,db.count("SELECT COUNT(*) FROM pet_order"));
    }
    @Test void changedPayloadCannotReuseAnExistingRequestKey() {
        Map<String,Object> body=input("2026-10-10T11:00");booking.create("13800000001",body);
        body.put("petName","另一只宠物");
        assertEquals(409,assertThrows(PetException.class,()->booking.create("13800000001",body)).status);
        assertEquals(1,db.count("SELECT COUNT(*) FROM pet_order"));
    }
    @Test void clientCannotOverrideServerPriceOrReviewAnUnfinishedOrder() {
        Map<String,Object> body=input("2026-10-10T11:00");body.put("priceCents",1);
        Map<String,Object> order=booking.create("13800000001",body);
        assertEquals(6900,((Number)order.get("priceCents")).intValue());
        assertThrows(PetException.class,()->booking.review("13800000001",(String)order.get("id"),map("stars",5,"content","未完成就评价")));
    }
    @Test void customerCannotReadOrModifyAnotherCustomersOrder() {
        String id=create();assertEquals(403,assertThrows(PetException.class,()->booking.order("13800000002",id,false)).status);
        assertThrows(PetException.class,()->booking.pay("13800000002",id));assertThrows(PetException.class,()->booking.cancel("13800000002",id));
        assertEquals("UNPAID",booking.order("13800000001",id,false).get("status"));
    }
    @Test void shiftAndStaffEditsCannotBreakExistingBookings() {
        String id=create();booking.pay("13800000001",id);
        assertThrows(PetException.class,()->booking.saveShift(map("staffId",staffId,"workDate","2026-10-10","startHour",12,"endHour",18),false));
        assertThrows(PetException.class,()->booking.saveStaff(staffId,map("name","小林","skills","CAT_BATH","active",1)));
        booking.cancel("13800000001",id);
        booking.saveShift(map("staffId",staffId,"workDate","2026-10-10","startHour",12,"endHour",18),false);
    }
    @Test void reassignMovesAllOccupiedSlotsWithoutChangingPrice() {
        String id=create();long replacement=((Number)db.one("SELECT id FROM pet_staff WHERE id<>? ORDER BY id LIMIT 1",staffId).get("id")).longValue();
        db.update("UPDATE pet_staff SET active=1,skills='DOG_BATH' WHERE id=?",replacement);
        booking.action(id,"reassign",map("staffId",replacement));
        assertEquals(0,db.count("SELECT COUNT(*) FROM pet_slot WHERE staff_id=?",staffId));
        assertEquals(1,db.count("SELECT COUNT(*) FROM pet_slot WHERE staff_id=?",replacement));
        assertEquals(6900,((Number)booking.order("13800000001",id,false).get("priceCents")).intValue());
    }
    @Test void priceAndDurationChangesDoNotAlterHistoricalOrders() {
        String id=create();Map<String,Object> changed=new LinkedHashMap<>(db.one("SELECT * FROM pet_spec WHERE id=?",specId));
        changed.put("priceCents",19900);changed.put("durationHours",2);booking.saveSpec(specId,changed);
        Map<String,Object> order=booking.order("13800000001",id,false);
        assertEquals(6900,((Number)order.get("priceCents")).intValue());assertEquals(1,((Number)order.get("durationHours")).intValue());
    }
    @Test void bookingAndCancellationBoundariesAreEnforced() {
        assertThrows(PetException.class,()->booking.create("13800000001",input("2026-10-10T09:00")));
        assertThrows(PetException.class,()->booking.create("13800000001",input("2026-10-10T11:30")));
        assertThrows(PetException.class,()->booking.create("13800000001",input("2026-10-17T11:00")));
        Map<String,Object> tooHeavy=input("2026-10-10T11:00");tooHeavy.put("weight",10.01);assertThrows(PetException.class,()->booking.create("13800000001",tooHeavy));
        String id=create();booking.pay("13800000001",id);booking.action(id,"confirm",map());clock.advance(Duration.ofMinutes(61));
        assertThrows(PetException.class,()->booking.cancel("13800000001",id));booking.action(id,"cancel",map("reason","顾客联系店主取消"));
        assertEquals(1,((Number)booking.order("13800000001",id,false).get("refunded")).intValue());
    }
    @Test void failedCodeAttemptsPersistAndCustomerCannotAccessOwnerEndpoints() throws Exception {
        String phone="13800000001";String code=(String)auth.code(phone).get("demoCode");
        for(int i=0;i<5;i++)assertThrows(PetException.class,()->auth.customerLogin(phone,"INVALID"));
        assertThrows(PetException.class,()->auth.customerLogin(phone,code));
        String other="13800000002",otherCode=(String)auth.code(other).get("demoCode");
        String token=(String)auth.customerLogin(other,otherCode).get("token");
        http.perform(get("/api/pet/owner/orders").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        http.perform(get("/api/pet/orders")).andExpect(status().isUnauthorized());
        http.perform(get("/api/pet/services")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
}
