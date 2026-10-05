package com.languagelean.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用系统级定时任务；关闭音频清理不能同时关闭一次性密钥的过期清理。 */
@Configuration @EnableScheduling
class SchedulingConfiguration {}
