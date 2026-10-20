package com.example.walletxfinance.controller;
import com.example.walletxfinance.dto.BankDtos.*;
import com.example.walletxfinance.service.BankConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1")
public class BankConnectionController {
    private final BankConnectionService service;
    private UUID owner(OAuth2AuthenticatedPrincipal principal) {return UUID.fromString(principal.getAttribute("sub"));}
    @PostMapping("/bank-connections/consents") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public ConsentView create(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ConsentRequest request) {return service.createConsent(owner(user),key,request);}
    @GetMapping("/bank-connections/consents")
    public PageView<ConsentView> list(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.consents(owner(user),page,size);}
    @GetMapping("/bank-connections/consents/{id}")
    public ConsentView get(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id) {return service.consent(owner(user),id);}
    @PostMapping("/bank-connections/consents/{id}/refresh")
    public ConsentView refresh(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id) {return service.refreshConsent(owner(user),id);}
    @PostMapping("/bank-connections/consents/{id}/revoke")
    public ConsentView revoke(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id) {return service.revoke(owner(user),id);}
    @PostMapping("/bank-connections/consents/{id}/sessions") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public SessionView createSession(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id,@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody SessionRequest request) {return service.createSession(owner(user),id,key,request);}
    @GetMapping("/bank-connections/consents/{id}/sessions")
    public PageView<SessionView> sessions(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.sessions(owner(user),id,page,size);}
    @GetMapping("/bank-connections/sessions/{id}")
    public SessionView session(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id) {return service.session(owner(user),id);}
    @PostMapping("/bank-connections/sessions/{id}/sync")
    public SessionView sync(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@PathVariable UUID id) {return service.sync(owner(user),id);}
    @GetMapping("/bank-accounts")
    public PageView<AccountView> accounts(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.accounts(owner(user),page,size);}
    @GetMapping("/transactions")
    public PageView<TransactionView> transactions(@AuthenticationPrincipal OAuth2AuthenticatedPrincipal user,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return service.transactions(owner(user),page,size);}
}
