package com.vn.ebook.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EbookAiProperties.class)
public class EbookAiConfig {
}
