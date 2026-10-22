package com.example.walletxfinance.exception;
import lombok.Getter;
@Getter
public class BankIntegrationException extends RuntimeException {
    private final int status;
    private final Integer providerStatus;
    public BankIntegrationException(int status, String message) { this(status,message,null); }
    public BankIntegrationException(int status, String message, Integer providerStatus) {
        super(message); this.status=status; this.providerStatus=providerStatus;
    }
}
