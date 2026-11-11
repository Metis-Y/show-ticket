package com.showticket.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务，供超时关单扫表兜底等使用。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}