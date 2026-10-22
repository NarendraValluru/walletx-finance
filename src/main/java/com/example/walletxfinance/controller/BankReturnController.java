package com.example.walletxfinance.controller;

import java.net.URI;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Browser navigation only. Provider query parameters never authorize access or establish consent. */
@RestController
public class BankReturnController {
    private final URI destination;

    public BankReturnController(@Value("${walletx.ui.bank-return-url:http://localhost:5173/banks/return}") String url) {
        destination=URI.create(url);
        String host=destination.getHost();
        boolean loopback=host!=null && Set.of("localhost","127.0.0.1","[::1]").contains(host);
        if(host==null || destination.getUserInfo()!=null || destination.getQuery()!=null || destination.getFragment()!=null
                || !("https".equals(destination.getScheme()) || (loopback && "http".equals(destination.getScheme()))))
            throw new IllegalArgumentException("WalletX UI return URL must be HTTPS, or loopback HTTP for local development, without query, fragment or user information");
    }

    @GetMapping("/bank-connection/result")
    public ResponseEntity<Void> result() {
        return ResponseEntity.status(HttpStatus.FOUND).location(destination)
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").build();
    }
}
