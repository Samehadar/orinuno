package com.orinuno;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.orinuno.repository")
public class OrinunoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrinunoApplication.class, args);
    }
}
