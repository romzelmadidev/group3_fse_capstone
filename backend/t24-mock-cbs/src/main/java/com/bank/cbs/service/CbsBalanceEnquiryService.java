package com.bank.cbs.service;

import com.bank.cbs.dto.BalanceEnquiryResponseDto;
import com.bank.cbs.entity.master.AccountMaster;
import com.bank.cbs.entity.master.BalanceMaster;
import com.bank.cbs.repository.master.AccountMasterRepository;
import com.bank.cbs.repository.master.BalanceMasterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CbsBalanceEnquiryService {

    private final BalanceMasterRepository balanceRepository;
    private final AccountMasterRepository accountRepository;

    public CbsBalanceEnquiryService(BalanceMasterRepository balanceRepository, AccountMasterRepository accountRepository) {
        this.balanceRepository = balanceRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(value = "masterTransactionManager", readOnly = true)
    public BalanceEnquiryResponseDto getBalanceByAccountId(String accountId) {
        BalanceMaster balance = balanceRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + accountId));
        AccountMaster account = accountRepository.findById(accountId).orElse(null);

        return mapToDto(balance, account);
    }

    @Transactional(value = "masterTransactionManager", readOnly = true)
    public BalanceEnquiryResponseDto getBalanceByAccountNumber(String accountNumber) {
        AccountMaster account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found with number: " + accountNumber));
        BalanceMaster balance = balanceRepository.findById(account.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Balance not found for account: " + accountNumber));

        return mapToDto(balance, account);
    }

    private BalanceEnquiryResponseDto mapToDto(BalanceMaster balance, AccountMaster account) {
        return new BalanceEnquiryResponseDto(
                balance.getAccountId(),
                account != null ? account.getAccountNumber() : "UNKNOWN",
                account != null ? account.getAccountType() : "SAVINGS",
                "PHP",
                balance.getBalanceAmount(),
                balance.getAvailableBalance(),
                balance.getHoldAmount(),
                account != null ? account.getStatus() : "ACTIVE"
        );
    }
}
