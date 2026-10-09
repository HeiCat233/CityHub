package com.tickethub;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.tickethub.mapper")
@EnableRabbit
@SpringBootApplication
public class TicketHubApplication {
    //
    public static void main(String[] args) {
        SpringApplication.run(TicketHubApplication.class, args);
    }

}
