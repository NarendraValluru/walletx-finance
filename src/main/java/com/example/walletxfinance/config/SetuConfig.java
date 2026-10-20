package com.example.walletxfinance.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @EnableScheduling @EnableConfigurationProperties(SetuProperties.class)
public class SetuConfig {
    @org.springframework.context.annotation.Bean
    org.springframework.transaction.support.TransactionTemplate bankTransactions(org.springframework.transaction.PlatformTransactionManager manager) {
        return new org.springframework.transaction.support.TransactionTemplate(manager);
    }
}
