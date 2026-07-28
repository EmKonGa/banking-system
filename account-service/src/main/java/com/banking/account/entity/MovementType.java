package com.banking.account.entity;

/**
 * What kind of money movement an {@link AccountTransferLog} row records. Deposits have no source
 * account, so the {@code from_*} columns are null for them — this is what tells the two apart
 * without inspecting nullability.
 */
public enum MovementType {
    TRANSFER, DEPOSIT
}
