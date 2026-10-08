package com.bank.cbs.dto;

import java.util.List;

public record MerkleVerificationRequestDto(
        String leafHash,
        String expectedMerkleRoot,
        List<MerkleProofDto.ProofStepDto> auditPath
) {}
