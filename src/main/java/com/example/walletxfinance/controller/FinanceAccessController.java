package com.example.walletxfinance.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.walletxfinance.dto.CurrentUserResponse;

@RestController
public class FinanceAccessController {
    @GetMapping("/api/v1/finance/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal principal) {
        List<String> roles = principal.getAttribute("roles");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                new CurrentUserResponse(UUID.fromString(principal.getAttribute("sub")), roles));
    }

    @GetMapping("/api/v1/finance/admin/status")
    public Map<String, String> adminStatus() {
        return Map.of("status", "authorized");
    }
}
