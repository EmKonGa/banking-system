package com.banking.account.repository;

import com.banking.account.entity.AccountTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountTransferLogRepository extends JpaRepository<AccountTransferLog, UUID> {}
