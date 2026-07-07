package com.banking.events;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceUpdate(UUID accountId, String accountNumber, BigDecimal balance) {}
