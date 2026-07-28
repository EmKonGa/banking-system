package com.banking.payment.entity;

public enum TransactionType {
    /** Money moved between two accounts inside the system. */
    TRANSFER,
    /** Money entered the system. No source account, so the {@code from_*} columns stay null. */
    DEPOSIT
}
