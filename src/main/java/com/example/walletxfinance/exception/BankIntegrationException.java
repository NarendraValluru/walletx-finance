package com.example.walletxfinance.exception;
import lombok.Getter;
@Getter
public class BankIntegrationException extends RuntimeException {
    private final int status;
    public BankIntegrationException(int status, String message) { super(message); this.status=status; }
}
