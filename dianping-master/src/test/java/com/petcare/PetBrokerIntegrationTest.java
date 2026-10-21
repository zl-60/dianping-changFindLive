package com.petcare;

import com.petcare.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real Redis + real RabbitMQ. Use a dedicated vhost, never a running application's vhost. */
@EnabledIfSystemProperty(named="pet.broker.tests",matches="true")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PetApplication.class,properties={
    "spring.config.name=pet","spring.profiles.active=demo,platform",
    "spring.datasource.url=jdbc:h2:mem:pet-broker;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.redis.port=${pet.test.redis-port:16379}","spring.redis.database=14",
    "spring.rabbitmq.port=${pet.test.rabbit-port:5672}","spring.rabbitmq.virtual-host=${pet.test.rabbit-vhost:/petcare-test}",
    "pet.expiry-enabled=false","pet.social-relay-enabled=false"
})
class PetBrokerIntegrationTest {
    @Autowired PetAuth auth;
    @Autowired PetData db;
    @Autowired PetCouponService coupons;
    @Autowired PetCouponRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Test void actualBrokerDeliversConfirmedMessageAndDuplicateIsSafe() throws Exception {
        // A previous test JVM may have stopped before draining Redis. This is the dedicated test DB.
        Set<String> keys=redis.keys(PetKeys.PREFIX+"*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        long coupon=((Number)db.one("SELECT id FROM pet_coupon ORDER BY id LIMIT 1").get("id")).longValue();coupons.publish(coupon);
        String phone="13900000099",code=auth.code(phone).get("demoCode").toString();auth.customerLogin(phone,code);
        long before=db.count("SELECT stock FROM pet_coupon WHERE id=?",coupon);
        String id=coupons.claim(coupon,phone).get("orderId").toString();relay.relay();
        long deadline=System.currentTimeMillis()+15000;
        while(db.count("SELECT COUNT(*) FROM pet_coupon_order WHERE id=?",Long.parseLong(id))==0&&System.currentTimeMillis()<deadline)Thread.sleep(100);
        assertEquals("SUCCESS",coupons.result(id,phone).get("state"));assertEquals(before-1,db.count("SELECT stock FROM pet_coupon WHERE id=?",coupon));
        CorrelationData confirm=new CorrelationData("duplicate-test");rabbit.convertAndSend(PetPlatformConfig.EXCHANGE,PetPlatformConfig.ROUTING,id,confirm);
        assertTrue(confirm.getFuture().get(5,TimeUnit.SECONDS).isAck());assertNull(confirm.getReturnedMessage());
        Thread.sleep(300);
        assertEquals(1,db.count("SELECT COUNT(*) FROM pet_coupon_order WHERE id=?",Long.parseLong(id)));
        assertEquals(before-1,db.count("SELECT stock FROM pet_coupon WHERE id=?",coupon));
        assertEquals(0L,redis.opsForZSet().zCard(PetKeys.PENDING));
    }
}
