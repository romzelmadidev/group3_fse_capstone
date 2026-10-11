package com.bank.cbs.service;

import com.bank.cbs.dto.MerkleProofDto;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class MerkleTreeService {

    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public String buildMerkleRoot(List<String> leafHashes) {
        if (leafHashes == null || leafHashes.isEmpty()) {
            return GENESIS_HASH;
        }
        if (leafHashes.size() == 1) {
            return leafHashes.get(0);
        }

        List<String> currentLayer = new ArrayList<>(leafHashes);
        while (currentLayer.size() > 1) {
            List<String> nextLayer = new ArrayList<>();
            if (currentLayer.size() % 2 != 0) {
                currentLayer.add(currentLayer.get(currentLayer.size() - 1));
            }
            for (int i = 0; i < currentLayer.size(); i += 2) {
                String left = currentLayer.get(i);
                String right = currentLayer.get(i + 1);
                nextLayer.add(calculateSha256(left + right));
            }
            currentLayer = nextLayer;
        }
        return currentLayer.get(0);
    }

    public List<MerkleProofDto.ProofStepDto> generateProof(List<String> leafHashes, int leafIndex) {
        if (leafHashes == null || leafIndex < 0 || leafIndex >= leafHashes.size()) {
            throw new IllegalArgumentException("Invalid leaf index or empty leaves list");
        }

        List<MerkleProofDto.ProofStepDto> proof = new ArrayList<>();
        List<String> currentLayer = new ArrayList<>(leafHashes);
        int currentIndex = leafIndex;

        while (currentLayer.size() > 1) {
            if (currentLayer.size() % 2 != 0) {
                currentLayer.add(currentLayer.get(currentLayer.size() - 1));
            }

            boolean isRightSibling;
            String siblingHash;
            if (currentIndex % 2 == 0) {
                siblingHash = currentLayer.get(currentIndex + 1);
                isRightSibling = true;
            } else {
                siblingHash = currentLayer.get(currentIndex - 1);
                isRightSibling = false;
            }
            proof.add(new MerkleProofDto.ProofStepDto(siblingHash, isRightSibling));

            List<String> nextLayer = new ArrayList<>();
            for (int i = 0; i < currentLayer.size(); i += 2) {
                String left = currentLayer.get(i);
                String right = currentLayer.get(i + 1);
                nextLayer.add(calculateSha256(left + right));
            }
            currentLayer = nextLayer;
            currentIndex = currentIndex / 2;
        }

        return proof;
    }

    public boolean verifyProof(String leafHash, String expectedRoot, List<MerkleProofDto.ProofStepDto> auditPath) {
        if (leafHash == null || expectedRoot == null) {
            return false;
        }
        if (auditPath == null || auditPath.isEmpty()) {
            return leafHash.equalsIgnoreCase(expectedRoot);
        }

        String currentHash = leafHash;
        for (MerkleProofDto.ProofStepDto step : auditPath) {
            if (step.isRightSibling()) {
                currentHash = calculateSha256(currentHash + step.siblingHash());
            } else {
                currentHash = calculateSha256(step.siblingHash() + currentHash);
            }
        }

        return currentHash.equalsIgnoreCase(expectedRoot);
    }
}
