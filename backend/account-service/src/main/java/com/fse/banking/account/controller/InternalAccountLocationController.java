package com.fse.banking.account.controller;

import com.fse.banking.account.model.AccountEntity;
import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.AccountRepository;
import com.fse.banking.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Current customer location by account, for the risk engine's travel-speed check.
 * Internal only: the gateway does not route /internal/**, so this is reachable on the
 * Docker network only (same posture as /internal/limits/**).
 */
@RestController
@RequiredArgsConstructor
public class InternalAccountLocationController {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @GetMapping("/internal/accounts/location")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> location(@RequestParam String accountId) {
        Optional<AccountEntity> account = accountRepository.findById(accountId)
                .or(() -> accountRepository.findByAccountNumber(accountId));
        if (account.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        UserEntity user = userRepository.findById(account.get().getUserId()).orElse(null);
        if (user == null || user.getLastKnownLatitude() == null || user.getLastKnownLongitude() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", user.getUserId());
        body.put("latitude", user.getLastKnownLatitude());
        body.put("longitude", user.getLastKnownLongitude());
        body.put("location_name", user.getLastKnownLocationName());
        body.put("ip_address", user.getLastKnownIp());
        return ResponseEntity.ok(body);
    }
}
