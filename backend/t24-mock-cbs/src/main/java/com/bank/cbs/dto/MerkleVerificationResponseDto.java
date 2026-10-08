package com.bank.cbs.dto;

public record MerkleVerificationResponseDto(
        boolean valid,
        String computedRoot,
        String expectedRoot,
        String message
) {}
