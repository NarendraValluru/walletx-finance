package com.example.walletxfinance.controller;
import com.example.walletxfinance.service.SetuWebhookService;
import com.example.walletxfinance.exception.BankIntegrationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.io.IOException;

@RestController @RequiredArgsConstructor
public class SetuWebhookController {
    private final SetuWebhookService service;
    @PostMapping(value="/api/v1/integrations/setu/webhook/{callbackToken}",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(@PathVariable String callbackToken,HttpServletRequest request) throws IOException {
        service.authenticate(callbackToken);
        byte[] body=request.getInputStream().readNBytes(65537);
        if(body.length>65536) throw new BankIntegrationException(413,"Notification exceeds 64 KiB; disable provider Auto-Fetch payload delivery");
        service.accept(body);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }
}
