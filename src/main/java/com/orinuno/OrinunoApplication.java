package com.orinuno;

import java.time.Clock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan("com.orinuno")
@MapperScan("com.orinuno.repository")
public class OrinunoApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrinunoApplication.class, args);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * Custom {@link TaskScheduler} so independent {@link
     * org.springframework.scheduling.annotation.Scheduled} jobs (request worker, link refresh,
     * stale-recovery, token validation) don't queue behind each other on Spring's default
     * single-threaded pool. See TECH_DEBT TD-PR-5.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("orinuno-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
