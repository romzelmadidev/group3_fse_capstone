package com.bank.cbs.service;

import com.bank.cbs.dto.MerkleProofDto;
import com.bank.cbs.dto.MerkleVerificationRequestDto;
import com.bank.cbs.dto.MerkleVerificationResponseDto;
import com.bank.cbs.entity.audit.AuditBlockAnchor;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.repository.audit.AuditBlockAnchorRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CbsAuditAnchoringService {

    private static final Logger log = LoggerFactory.getLogger(CbsAuditAnchoringService.class);

    private final LedgerMutationAuditRepository ledgerMutationAuditRepository;
    private final AuditBlockAnchorRepository auditBlockAnchorRepository;
    private final MerkleTreeService merkleTreeService;

    public CbsAuditAnchoringService(
            LedgerMutationAuditRepository ledgerMutationAuditRepository,
            AuditBlockAnchorRepository auditBlockAnchorRepository,
            MerkleTreeService merkleTreeService) {
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
        this.auditBlockAnchorRepository = auditBlockAnchorRepository;
        this.merkleTreeService = merkleTreeService;
    }

    @Transactional("auditTransactionManager")
    public Optional<AuditBlockAnchor> anchorCurrentBlock() {
        Optional<AuditBlockAnchor> lastBlock = auditBlockAnchorRepository.findTopByOrderByBlockNumberDesc();
        Long lastEndAuditId = lastBlock.map(AuditBlockAnchor::getEndAuditId).orElse(0L);

        List<LedgerMutationAudit> unanchored = ledgerMutationAuditRepository.findByAuditIdGreaterThanOrderByAuditIdAsc(lastEndAuditId);
        if (unanchored.isEmpty()) {
            log.info("No unanchored audit records to anchor.");
            return Optional.empty();
        }

        List<String> leafHashes = unanchored.stream()
                .map(LedgerMutationAudit::getSha256Hash)
                .toList();

        String merkleRoot = merkleTreeService.buildMerkleRoot(leafHashes);
        long nextBlockNumber = lastBlock.map(b -> b.getBlockNumber() + 1).orElse(1L);
        String prevBlockRoot = lastBlock.map(AuditBlockAnchor::getBlockHash).orElse(MerkleTreeService.GENESIS_HASH);

        String blockHash = merkleTreeService.calculateSha256(merkleRoot + "|" + prevBlockRoot + "|" + nextBlockNumber);

        Long startAuditId = unanchored.get(0).getAuditId();
        Long endAuditId = unanchored.get(unanchored.size() - 1).getAuditId();

        AuditBlockAnchor anchor = AuditBlockAnchor.builder()
                .blockNumber(nextBlockNumber)
                .startAuditId(startAuditId)
                .endAuditId(endAuditId)
                .recordCount(unanchored.size())
                .merkleRoot(merkleRoot)
                .prevBlockRoot(prevBlockRoot)
                .blockHash(blockHash)
                .createdAt(Instant.now())
                .build();

        AuditBlockAnchor saved = auditBlockAnchorRepository.save(anchor);
        log.info("Anchored audit block #{} with {} records (audit IDs {} to {}). Merkle root: {}, Block hash: {}",
                saved.getBlockNumber(), saved.getRecordCount(), saved.getStartAuditId(), saved.getEndAuditId(),
                saved.getMerkleRoot(), saved.getBlockHash());
        return Optional.of(saved);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<AuditBlockAnchor> getAllBlocks() {
        return auditBlockAnchorRepository.findAllByOrderByBlockNumberAsc();
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public Optional<AuditBlockAnchor> getBlockByNumber(Long blockNumber) {
        return auditBlockAnchorRepository.findByBlockNumber(blockNumber);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public MerkleProofDto generateProofForTransaction(String transactionId) {
        LedgerMutationAudit targetAudit = ledgerMutationAuditRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Audit record not found for transaction: " + transactionId));

        Long auditId = targetAudit.getAuditId();
        AuditBlockAnchor block = auditBlockAnchorRepository.findFirstByStartAuditIdLessThanEqualAndEndAuditIdGreaterThanEqual(auditId, auditId)
                .orElseThrow(() -> new IllegalStateException("Transaction " + transactionId + " (audit ID " + auditId + ") has not yet been anchored into a Merkle block"));

        List<LedgerMutationAudit> blockAudits = ledgerMutationAuditRepository.findByAuditIdBetweenOrderByAuditIdAsc(
                block.getStartAuditId(), block.getEndAuditId()
        );

        List<String> leafHashes = blockAudits.stream()
                .map(LedgerMutationAudit::getSha256Hash)
                .toList();

        int targetIndex = -1;
        for (int i = 0; i < blockAudits.size(); i++) {
            if (blockAudits.get(i).getAuditId().equals(auditId)) {
                targetIndex = i;
                break;
            }
        }

        if (targetIndex == -1) {
            throw new IllegalStateException("Audit record not found in block range");
        }

        List<MerkleProofDto.ProofStepDto> path = merkleTreeService.generateProof(leafHashes, targetIndex);

        return new MerkleProofDto(
                targetAudit.getTransactionId(),
                targetAudit.getAuditId(),
                block.getBlockNumber(),
                targetAudit.getSha256Hash(),
                block.getMerkleRoot(),
                block.getBlockHash(),
                targetIndex,
                blockAudits.size(),
                path
        );
    }

    public MerkleVerificationResponseDto verifyProof(MerkleVerificationRequestDto request) {
        boolean valid = merkleTreeService.verifyProof(
                request.leafHash(),
                request.expectedMerkleRoot(),
                request.auditPath()
        );

        return new MerkleVerificationResponseDto(
                valid,
                valid ? request.expectedMerkleRoot() : "MISMATCH",
                request.expectedMerkleRoot(),
                valid ? "Cryptographic Merkle inclusion proof verified successfully against block root"
                      : "Cryptographic Merkle verification failed: computed root does not match expected block root"
        );
    }
}
