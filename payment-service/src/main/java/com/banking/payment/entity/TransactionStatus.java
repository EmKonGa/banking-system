package com.banking.payment.entity;

public enum TransactionStatus {
    /**
     * The intent is recorded but the outcome is unknown — payment-service has asked account-service
     * to move the money and has not yet learned whether it did. A row sits here only between those
     * two points, or after a crash in between; {@code TransferRecoveryPoller} settles the strays.
     */
    PENDING,
    COMPLETED,
    FAILED
}
