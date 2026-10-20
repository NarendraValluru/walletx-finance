package com.example.walletxfinance.exception;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
@RestControllerAdvice
public class FinanceErrors {
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> conflict(Exception e) {
        return ResponseEntity.status(409).body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,"Conflicting or concurrent request; reuse the same Idempotency-Key and check its existing status"));
    }
    @ExceptionHandler(BankIntegrationException.class)
    ResponseEntity<ProblemDetail> integration(BankIntegrationException e) {
        return ResponseEntity.status(e.getStatus()).body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.getStatus()),e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,"Invalid request parameters"));
    }
}
