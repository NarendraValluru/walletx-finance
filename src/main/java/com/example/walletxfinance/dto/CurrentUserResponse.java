package com.example.walletxfinance.dto;

import java.util.List;
import java.util.UUID;

public record CurrentUserResponse(UUID userId, List<String> roles) {}
