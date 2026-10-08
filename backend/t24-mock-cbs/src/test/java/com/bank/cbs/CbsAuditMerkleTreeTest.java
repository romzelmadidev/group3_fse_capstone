package com.bank.cbs;

import com.bank.cbs.dto.MerkleProofDto;
import com.bank.cbs.dto.MerkleVerificationRequestDto;
import com.bank.cbs.dto.MerkleVerificationResponseDto;
import com.bank.cbs.entity.audit.AuditBlockAnchor;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.repository.audit.AuditBlockAnchorRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import com.bank.cbs.service.CbsAuditAnchoringService;
import com.bank.cbs.service.MerkleTreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsAuditMerkleTreeTest {

    private MerkleTreeService merkleTreeService;

    @Mock
    private LedgerMutationAuditRepository ledgerMutationAuditRepository;

    @Mock
    private AuditBlockAnchorRepository auditBlockAnchorRepository;

    private CbsAuditAnchoringService anchoringService;

    @BeforeEach
    void setUp() {
        merkleTreeService = new MerkleTreeService();
        anchoringService = new CbsAuditAnchoringService(
                ledgerMutationAuditRepository,
                auditBlockAnchorRepository,
                merkleTreeService
        );
    }

    @Test
    void testMerkleTreeService_BuildRootAndVerifyProof_FourLeaves() {
        List<String> leaves = List.of(
                merkleTreeService.calculateSha256("TX-1-DR"),
                merkleTreeService.calculateSha256("TX-1-CR"),
                merkleTreeService.calculateSha256("TX-2-DR"),
                merkleTreeService.calculateSha256("TX-2-CR")
        );

        String root = merkleTreeService.buildMerkleRoot(leaves);
        assertNotNull(root);
        assertEquals(64, root.length());

        // Test proof for every leaf
        for (int i = 0; i < leaves.size(); i++) {
            List<MerkleProofDto.ProofStepDto> proof = merkleTreeService.generateProof(leaves, i);
            assertEquals(2, proof.size(), "Height of balanced 4-leaf tree proof should be 2");

            boolean isValid = merkleTreeService.verifyProof(leaves.get(i), root, proof);
            assertTrue(isValid, "Proof for leaf index " + i + " must verify successfully");
        }
    }

    @Test
    void testMerkleTreeService_BuildRootAndVerifyProof_OddNumberOfLeaves() {
        // 3 leaves tests odd leaf duplication
        List<String> leaves = List.of(
                merkleTreeService.calculateSha256("TX-1-DR"),
                merkleTreeService.calculateSha256("TX-1-CR"),
                merkleTreeService.calculateSha256("TX-2-DR")
        );

        String root = merkleTreeService.buildMerkleRoot(leaves);
        assertNotNull(root);

        for (int i = 0; i < leaves.size(); i++) {
            List<MerkleProofDto.ProofStepDto> proof = merkleTreeService.generateProof(leaves, i);
            boolean isValid = merkleTreeService.verifyProof(leaves.get(i), root, proof);
            assertTrue(isValid, "Proof for odd-count leaf " + i + " must verify successfully");
        }
    }

    @Test
    void testMerkleTreeService_TamperedProof_FailsVerification() {
        List<String> leaves = List.of(
                merkleTreeService.calculateSha256("TX-1-DR"),
                merkleTreeService.calculateSha256("TX-1-CR")
        );

        String root = merkleTreeService.buildMerkleRoot(leaves);
        List<MerkleProofDto.ProofStepDto> proof = merkleTreeService.generateProof(leaves, 0);

        // Tamper leaf
        String tamperedLeaf = merkleTreeService.calculateSha256("TX-TAMPERED");
        assertFalse(merkleTreeService.verifyProof(tamperedLeaf, root, proof));

        // Tamper root
        String fakeRoot = merkleTreeService.calculateSha256("FAKE-ROOT");
        assertFalse(merkleTreeService.verifyProof(leaves.get(0), fakeRoot, proof));
    }

    @Test
    void testAuditAnchoringService_AnchorCurrentBlock_CreatesBlockWithChainedRoot() {
        // Mock unanchored records
        when(auditBlockAnchorRepository.findTopByOrderByBlockNumberDesc()).thenReturn(Optional.empty());

        List<LedgerMutationAudit> unanchored = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            unanchored.add(LedgerMutationAudit.builder()
                    .auditId(i)
                    .transactionId("TX-" + i)
                    .accountId("ACC-" + i)
                    .mutationType("TRANSFER")
                    .mutationAmount(new BigDecimal("1000.00"))
                    .beforeBalance(new BigDecimal("5000.00"))
                    .afterBalance(new BigDecimal("4000.00"))
                    .initiatorUserId("SYSTEM")
                    .status("COMMITTED")
                    .sha256Hash(merkleTreeService.calculateSha256("TX-DATA-" + i))
                    .createdAt(Instant.now())
                    .build());
        }

        when(ledgerMutationAuditRepository.findByAuditIdGreaterThanOrderByAuditIdAsc(0L)).thenReturn(unanchored);
        when(auditBlockAnchorRepository.save(any(AuditBlockAnchor.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<AuditBlockAnchor> blockOpt = anchoringService.anchorCurrentBlock();

        assertTrue(blockOpt.isPresent());
        AuditBlockAnchor block = blockOpt.get();
        assertEquals(1L, block.getBlockNumber());
        assertEquals(1L, block.getStartAuditId());
        assertEquals(4L, block.getEndAuditId());
        assertEquals(4, block.getRecordCount());
        assertEquals(MerkleTreeService.GENESIS_HASH, block.getPrevBlockRoot());
        assertNotNull(block.getMerkleRoot());
        assertNotNull(block.getBlockHash());
        verify(auditBlockAnchorRepository, times(1)).save(any(AuditBlockAnchor.class));
    }

    @Test
    void testAuditAnchoringService_GenerateProofAndVerify_EndToEnd() {
        String txId = "TX-TARGET-77";
        String targetHash = merkleTreeService.calculateSha256("TX-DATA-77");

        LedgerMutationAudit targetAudit = LedgerMutationAudit.builder()
                .auditId(10L)
                .transactionId(txId)
                .accountId("ACC-1")
                .mutationType("TRANSFER")
                .mutationAmount(new BigDecimal("500.00"))
                .beforeBalance(new BigDecimal("2000.00"))
                .afterBalance(new BigDecimal("1500.00"))
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .sha256Hash(targetHash)
                .build();

        when(ledgerMutationAuditRepository.findByTransactionId(txId)).thenReturn(Optional.of(targetAudit));

        AuditBlockAnchor block = AuditBlockAnchor.builder()
                .blockId(1L)
                .blockNumber(1L)
                .startAuditId(9L)
                .endAuditId(10L)
                .recordCount(2)
                .prevBlockRoot(MerkleTreeService.GENESIS_HASH)
                .build();

        LedgerMutationAudit siblingAudit = LedgerMutationAudit.builder()
                .auditId(9L)
                .transactionId("TX-SIBLING")
                .sha256Hash(merkleTreeService.calculateSha256("SIBLING-DATA"))
                .build();

        List<LedgerMutationAudit> blockAudits = List.of(siblingAudit, targetAudit);
        String calculatedRoot = merkleTreeService.buildMerkleRoot(List.of(siblingAudit.getSha256Hash(), targetAudit.getSha256Hash()));
        block.setMerkleRoot(calculatedRoot);
        block.setBlockHash(merkleTreeService.calculateSha256(calculatedRoot + "|" + block.getPrevBlockRoot() + "|1"));

        when(auditBlockAnchorRepository.findFirstByStartAuditIdLessThanEqualAndEndAuditIdGreaterThanEqual(10L, 10L))
                .thenReturn(Optional.of(block));
        when(ledgerMutationAuditRepository.findByAuditIdBetweenOrderByAuditIdAsc(9L, 10L))
                .thenReturn(blockAudits);

        MerkleProofDto proof = anchoringService.generateProofForTransaction(txId);

        assertNotNull(proof);
        assertEquals(txId, proof.transactionId());
        assertEquals(10L, proof.auditId());
        assertEquals(1L, proof.blockNumber());
        assertEquals(targetHash, proof.leafHash());
        assertEquals(calculatedRoot, proof.merkleRoot());
        assertEquals(1, proof.leafIndex());
        assertEquals(2, proof.totalLeaves());

        // Verify proof using verification endpoint service logic
        MerkleVerificationRequestDto verifyRequest = new MerkleVerificationRequestDto(
                proof.leafHash(),
                proof.merkleRoot(),
                proof.auditPath()
        );
        MerkleVerificationResponseDto verifyResponse = anchoringService.verifyProof(verifyRequest);

        assertTrue(verifyResponse.valid());
        assertEquals(calculatedRoot, verifyResponse.computedRoot());
        assertTrue(verifyResponse.message().contains("verified successfully"));
    }
}
