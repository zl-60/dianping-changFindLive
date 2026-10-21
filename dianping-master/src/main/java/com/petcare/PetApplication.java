package com.petcare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Collections;

/** Isolated entry point: the original Dianping application and tables remain available. */
@EnableScheduling
@SpringBootApplication(proxyBeanMethods = false)
public class PetApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PetApplication.class);
        application.setDefaultProperties(Collections.singletonMap("spring.config.name", "pet"));
        application.run(args);
    }

    @Bean
    public Clock petClock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
}
