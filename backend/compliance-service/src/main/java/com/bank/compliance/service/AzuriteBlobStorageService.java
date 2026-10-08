package com.bank.compliance.service;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class AzuriteBlobStorageService {

    private static final Logger log = LoggerFactory.getLogger(AzuriteBlobStorageService.class);

    private final BlobServiceClient blobServiceClient;
    private final String containerName;
    private BlobContainerClient containerClient;

    public AzuriteBlobStorageService(
            BlobServiceClient blobServiceClient,
            @Value("${azure.storage.container-name:compliance-vault}") String containerName) {
        this.blobServiceClient = blobServiceClient;
        this.containerName = containerName;
    }

    @PostConstruct
    public void init() {
        try {
            this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
            if (!containerClient.exists()) {
                containerClient.create();
                log.info("Initialized Azurite blob container: {}", containerName);
            } else {
                log.info("Connected to existing Azurite blob container: {}", containerName);
            }
        } catch (Exception e) {
            log.warn("Could not automatically create Azurite container on startup: {}. Will retry on upload.", e.getMessage());
        }
    }

    public record UploadResult(
            String blobName,
            String storageUri,
            String sha256Checksum,
            long sizeBytes
    ) {}

    public UploadResult uploadArtifact(String blobName, byte[] data, String contentType) {
        ensureContainerExists();
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        String checksum = computeSha256(data);
        log.info("Uploading artifact to Azurite: blob={}, size={} bytes, sha256={}", blobName, data.length, checksum);

        BlobHttpHeaders headers = new BlobHttpHeaders().setContentType(contentType);
        blobClient.upload(BinaryData.fromBytes(data), true);
        blobClient.setHttpHeaders(headers);

        String uri = "azure-blob://" + containerName + "/" + blobName;
        return new UploadResult(blobName, uri, checksum, data.length);
    }

    public byte[] downloadArtifact(String blobName) {
        ensureContainerExists();
        BlobClient blobClient = containerClient.getBlobClient(blobName);
        if (!blobClient.exists()) {
            throw new IllegalArgumentException("Artifact not found in Azurite: " + blobName);
        }
        return blobClient.downloadContent().toBytes();
    }

    private void ensureContainerExists() {
        if (this.containerClient == null) {
            this.containerClient = blobServiceClient.getBlobContainerClient(containerName);
        }
        if (!containerClient.exists()) {
            containerClient.create();
        }
    }

    public record BlobItemDto(
            String blobName,
            String storageUri,
            long sizeBytes,
            String contentType,
            java.time.Instant lastModified
    ) {}

    public java.util.List<BlobItemDto> listArtifacts() {
        ensureContainerExists();
        java.util.List<BlobItemDto> list = new java.util.ArrayList<>();
        try {
            containerClient.listBlobs().forEach(item -> {
                long size = item.getProperties() != null && item.getProperties().getContentLength() != null
                        ? item.getProperties().getContentLength()
                        : 0L;
                String ctype = item.getProperties() != null && item.getProperties().getContentType() != null
                        ? item.getProperties().getContentType()
                        : (item.getName().endsWith(".pdf") ? "application/pdf" : (item.getName().endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/octet-stream"));
                java.time.Instant mod = item.getProperties() != null && item.getProperties().getLastModified() != null
                        ? item.getProperties().getLastModified().toInstant()
                        : java.time.Instant.now();
                list.add(new BlobItemDto(
                        item.getName(),
                        "azure-blob://" + containerName + "/" + item.getName(),
                        size,
                        ctype,
                        mod
                ));
            });
        } catch (Exception e) {
            log.warn("Could not list blobs from Azurite container {}: {}", containerName, e.getMessage());
        }
        return list;
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

