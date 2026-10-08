package com.bank.cbs.dto;

import java.util.List;

public record MerkleProofDto(
        String transactionId,
        Long auditId,
        Long blockNumber,
        String leafHash,
        String merkleRoot,
        String blockHash,
        int leafIndex,
        int totalLeaves,
        List<ProofStepDto> auditPath
) {
    public record ProofStepDto(
            String siblingHash,
            boolean isRightSibling
    ) {}
}
