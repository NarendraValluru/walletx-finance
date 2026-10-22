package com.example.walletxfinance.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="walletx.sandbox-ui.enabled", havingValue="true", matchIfMissing=true)
public class BankSandboxPageController {
    @GetMapping("/bank-connection/sandbox")
    public ResponseEntity<Resource> page() {
        return resource("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/bank-connection/sandbox.js")
    public ResponseEntity<Resource> script() {
        return resource("sandbox.js", MediaType.parseMediaType("application/javascript"));
    }

    private ResponseEntity<Resource> resource(String name, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                .body(new ClassPathResource("bank-sandbox/"+name));
    }
}
