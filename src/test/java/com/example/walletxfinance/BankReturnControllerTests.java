package com.example.walletxfinance;

import com.example.walletxfinance.controller.BankReturnController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankReturnControllerTests {
    @Test void acceptsOnlyTrustedConfiguredHttpsOrLoopbackDestinations() {
        assertDoesNotThrow(() -> new BankReturnController("https://walletx.example/banks/return"));
        assertDoesNotThrow(() -> new BankReturnController("http://127.0.0.1:5173/banks/return"));
        for(String url:new String[]{"//evil.example", "http://evil.example/banks/return", "javascript:alert(1)",
                "https://user:pass@walletx.example/", "https://walletx.example/?token=secret", "https://walletx.example/#token=secret"}) {
            assertThrows(IllegalArgumentException.class, () -> new BankReturnController(url));
        }
    }
}
