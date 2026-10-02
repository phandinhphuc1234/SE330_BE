package com.vn.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.TimeZone;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApplicationTimeProperties.class)
@RequiredArgsConstructor
public class ApplicationTimeConfig {

    private final ApplicationTimeProperties properties;

    @PostConstruct
    void configureJvmDefaultTimeZone() {
        // Keep legacy JDBC/JPA timestamp-without-time-zone mappings compatible while
        // all new business-time decisions use the injectable Clock below.
        TimeZone.setDefault(TimeZone.getTimeZone(properties.zone()));
    }

    @Bean
    public Clock applicationClock() {
        return Clock.system(properties.zone());
    }
}
