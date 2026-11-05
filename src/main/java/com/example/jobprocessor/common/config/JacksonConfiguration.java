package com.example.jobprocessor.common.config;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    /** Bounds JSON nesting so a small but deeply nested payload cannot exhaust the parser. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonNestingLimit(JobProcessorProperties properties) {
        int maxDepth = properties.api().maxJsonNestingDepth();
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(maxDepth).build()));
    }
}
