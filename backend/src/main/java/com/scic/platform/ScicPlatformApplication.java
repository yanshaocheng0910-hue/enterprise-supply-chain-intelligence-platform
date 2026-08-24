package com.scic.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("com.scic.platform.masterdata")
@SpringBootApplication
public class ScicPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScicPlatformApplication.class, args);
    }
}

