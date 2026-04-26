package com.orinuno;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.orinuno.repository")
public class OrinunoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrinunoApplication.class, args);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
