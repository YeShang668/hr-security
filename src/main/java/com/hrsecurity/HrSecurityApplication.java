package com.hrsecurity;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动类：@MapperScan 一次性扫描所有 MyBatis-Plus Mapper，省去每个 Mapper 上加 @Mapper
 */
@SpringBootApplication
@MapperScan("com.hrsecurity.mapper")
public class HrSecurityApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrSecurityApplication.class, args);
    }
}
