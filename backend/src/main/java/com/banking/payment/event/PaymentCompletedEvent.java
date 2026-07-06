package com.banking.payment.event;

import com.banking.account.dto.AccountResponse;
import com.banking.payment.dto.TransactionResponse;

public record PaymentCompletedEvent(
        String fromUserId,
        String toUserId,
        AccountResponse fromSnapshot,
        AccountResponse toSnapshot,
        TransactionResponse txSnapshot
) {}
