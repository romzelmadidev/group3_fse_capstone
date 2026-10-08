package com.bank.cbs.service;

import com.bank.cbs.dto.HoldRequestDto;
import com.bank.cbs.dto.HoldResponseDto;
import com.bank.cbs.entity.master.BalanceMaster;
import com.bank.cbs.repository.master.BalanceMasterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class CbsHoldService {

    private static final Logger log = LoggerFactory.getLogger(CbsHoldService.class);

    private final BalanceMasterRepository balanceRepository;

    public CbsHoldService(BalanceMasterRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
    }

    @Transactional("masterTransactionManager")
    public HoldResponseDto placeHold(HoldRequestDto request) {
        log.info("Authoritatively placing CBS fund hold: accountId={}, amount={}, ref={}",
                request.accountId(), request.amount(), request.referenceId());

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Hold amount must be positive.");
        }

        BalanceMaster balance = balanceRepository.findByAccountIdForUpdate(request.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + request.accountId()));

        BigDecimal currentHold = balance.getHoldAmount() != null ? balance.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal availableBalance = balance.getAvailableBalance();
        if (availableBalance == null) {
            availableBalance = balance.getBalanceAmount().subtract(currentHold);
        }

        if (availableBalance.compareTo(request.amount()) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient available balance to place hold. Account %s has available %s, requested hold %s",
                    request.accountId(), availableBalance, request.amount()));
        }

        BigDecimal newHold = currentHold.add(request.amount());
        BigDecimal newAvailable = balance.getBalanceAmount().subtract(newHold);

        balance.setHoldAmount(newHold);
        balance.setAvailableBalance(newAvailable);
        balance.setUpdatedAt(Instant.now());
        balanceRepository.save(balance);

        log.info("CBS hold placed: accountId={}, newHoldAmount={}, availableBalance={}",
                request.accountId(), newHold, newAvailable);

        return new HoldResponseDto(
                request.referenceId(),
                request.accountId(),
                request.amount(),
                "HELD",
                balance.getBalanceAmount(),
                newHold,
                newAvailable,
                "Authoritative fund hold placed successfully",
                Instant.now()
        );
    }

    @Transactional("masterTransactionManager")
    public HoldResponseDto releaseHold(HoldRequestDto request) {
        log.info("Authoritatively releasing CBS fund hold: accountId={}, amount={}, ref={}",
                request.accountId(), request.amount(), request.referenceId());

        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Release amount must be positive.");
        }

        BalanceMaster balance = balanceRepository.findByAccountIdForUpdate(request.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + request.accountId()));

        BigDecimal currentHold = balance.getHoldAmount() != null ? balance.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal newHold = currentHold.subtract(request.amount());
        if (newHold.compareTo(BigDecimal.ZERO) < 0) {
            newHold = BigDecimal.ZERO;
        }
        BigDecimal newAvailable = balance.getBalanceAmount().subtract(newHold);

        balance.setHoldAmount(newHold);
        balance.setAvailableBalance(newAvailable);
        balance.setUpdatedAt(Instant.now());
        balanceRepository.save(balance);

        log.info("CBS hold released: accountId={}, newHoldAmount={}, availableBalance={}",
                request.accountId(), newHold, newAvailable);

        return new HoldResponseDto(
                request.referenceId(),
                request.accountId(),
                request.amount(),
                "RELEASED",
                balance.getBalanceAmount(),
                newHold,
                newAvailable,
                "Authoritative fund hold released successfully",
                Instant.now()
        );
    }
}
