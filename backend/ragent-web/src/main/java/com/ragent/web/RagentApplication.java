package com.ragent.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 主应用入口
 */
@SpringBootApplication(scanBasePackages = "com.ragent")
@ConfigurationPropertiesScan("com.ragent")
public class RagentApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagentApplication.class, args);
    }
}
