package com.example.jobprocessor.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobProcessorProperties.class)
public class JobProcessorConfiguration {
}
