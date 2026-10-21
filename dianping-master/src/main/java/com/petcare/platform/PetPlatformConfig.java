package com.petcare.platform;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration @Profile("platform")
@MapperScan("com.petcare.platform.mapper")
@Import({CacheClient.class,RedisIdWorker.class})
public class PetPlatformConfig {
    public static final String EXCHANGE="petcare.coupon";
    public static final String ROUTING="claim";
    public static final String QUEUE="petcare.coupon.orders";
    @Bean(destroyMethod="shutdown")
    public RedissonClient petRedisson(@Value("${spring.redis.host}") String host,
        @Value("${spring.redis.port}") int port,@Value("${spring.redis.password:}") String password,
        @Value("${spring.redis.database}") int database) {
        Config config=new Config();
        config.setThreads(2).setNettyThreads(2).setLockWatchdogTimeout(30000);
        org.redisson.config.SingleServerConfig server=config.useSingleServer()
            .setAddress("redis://"+host+":"+port).setDatabase(database)
            .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
            .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        if(!password.isEmpty()) server.setPassword(password);
        return Redisson.create(config);
    }
    @Bean public Declarables petCouponTopology() {
        DirectExchange exchange=new DirectExchange(EXCHANGE,true,false);
        DirectExchange retry=new DirectExchange(EXCHANGE+".retry",true,false);
        Queue orders=QueueBuilder.durable(QUEUE).deadLetterExchange(retry.getName()).deadLetterRoutingKey(ROUTING).build();
        Queue delayed=QueueBuilder.durable(QUEUE+".retry").ttl(30000)
            .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(ROUTING).build();
        return new Declarables(exchange,retry,orders,delayed,
            BindingBuilder.bind(orders).to(exchange).with(ROUTING),
            BindingBuilder.bind(delayed).to(retry).with(ROUTING));
    }
}
