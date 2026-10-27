package com.showticket;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@MapperScan("com.showticket.mapper")
@SpringBootApplication
@EnableAspectJAutoProxy(exposeProxy = true)  //动态代理
public class ShowTicketApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShowTicketApplication.class, args);
    }

}
