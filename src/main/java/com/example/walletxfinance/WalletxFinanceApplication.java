package com.example.walletxfinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import com.example.backendutil.finance.BankConsentEntity;

@SpringBootApplication
@EntityScan(basePackageClasses = BankConsentEntity.class)
public class WalletxFinanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletxFinanceApplication.class, args);
    }
}
