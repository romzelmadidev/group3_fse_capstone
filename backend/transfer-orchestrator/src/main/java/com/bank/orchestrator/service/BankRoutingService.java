package com.bank.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves participant bank routing, Nostro clearing buffers, and endpoint URLs.
 * Enforces a strict 3-tier priority hierarchy:
 * 1. Environment Variable Override (PARTNER_BANK_URL / COOBS_API_ENDPOINT_URL / BANK_<CODE>_URL)
 * 2. Database / Participant Directory Configuration
 * 3. Default Local Simulator Fallback
 */
@Service
public class BankRoutingService {

    private static final Logger log = LoggerFactory.getLogger(BankRoutingService.class);

    private final Environment environment;
    private final String defaultPartnerUrl;
    private final Map<String, BankInfo> staticRegistry = new ConcurrentHashMap<>();

    public record BankInfo(
            String bankCode,
            String bankName,
            String bicSwiftCode,
            String clearingNetwork,
            String nostroAccountId,
            String defaultApiUrl,
            boolean isActive
    ) {}

    public BankRoutingService(
            Environment environment,
            @Value("${services.partner.url:http://localhost:8082/api/v1/mock-partner/transfers}") String defaultPartnerUrl) {
        this.environment = environment;
        this.defaultPartnerUrl = defaultPartnerUrl;

        // Baseline participants
        staticRegistry.put("COOBS", new BankInfo(
                "COOBS",
                "Cooperative Overseas Banking System (CooBS)",
                "COOBPHM2XXX",
                "BILATERAL",
                "ACC-NOSTRO-BANK-B",
                defaultPartnerUrl,
                true
        ));
        staticRegistry.put("AURABANK", new BankInfo(
                "AURABANK",
                "AuraBank Core Retail",
                "AURAPHM1XXX",
                "CORE",
                "ACC-NOSTRO-BANK-B",
                null,
                true
        ));
    }

    /**
     * Resolves the effective HTTP endpoint URL for dispatching an outward wire.
     * Tier 1: Specific env vars (e.g. COOBS_API_ENDPOINT_URL, PARTNER_BANK_URL, BANK_COOBS_URL).
     * Tier 2: Dynamic / registered bank endpoint.
     * Tier 3: Local partner simulator fallback.
     */
    public String resolveEffectiveEndpointUrl(String targetBankCode) {
        if (targetBankCode == null || targetBankCode.isBlank()) {
            targetBankCode = "COOBS";
        }
        String normalized = targetBankCode.trim().toUpperCase();

        // 1. Check specific bank endpoint env var
        String specificEnv = environment.getProperty(normalized + "_API_ENDPOINT_URL");
        if (specificEnv != null && !specificEnv.isBlank()) {
            log.info("Routing {} wire to endpoint from {}_API_ENDPOINT_URL: {}", normalized, normalized, specificEnv);
            return specificEnv.trim();
        }

        String genericEnv = environment.getProperty("BANK_" + normalized + "_URL");
        if (genericEnv != null && !genericEnv.isBlank()) {
            log.info("Routing {} wire to endpoint from BANK_{}_URL: {}", normalized, normalized, genericEnv);
            return genericEnv.trim();
        }

        // For COOBS, check PARTNER_BANK_URL and services.partner.url
        if ("COOBS".equals(normalized)) {
            String partnerEnv = environment.getProperty("PARTNER_BANK_URL");
            if (partnerEnv != null && !partnerEnv.isBlank()) {
                log.info("Routing COOBS wire to endpoint from PARTNER_BANK_URL: {}", partnerEnv);
                return partnerEnv.trim();
            }
            String propUrl = environment.getProperty("services.partner.url");
            if (propUrl != null && !propUrl.isBlank()) {
                return propUrl.trim();
            }
        }

        // 2. Check static/database registry
        BankInfo info = staticRegistry.get(normalized);
        if (info != null && info.defaultApiUrl() != null && !info.defaultApiUrl().isBlank()) {
            return info.defaultApiUrl().trim();
        }

        // 3. Fallback
        return defaultPartnerUrl;
    }

    /**
     * Resolves the internal Nostro settlement buffer account for the specified partner bank.
     */
    public String resolveNostroAccount(String targetBankCode) {
        if (targetBankCode == null || targetBankCode.isBlank()) {
            return "ACC-NOSTRO-BANK-B";
        }
        BankInfo info = staticRegistry.get(targetBankCode.trim().toUpperCase());
        if (info != null && info.nostroAccountId() != null) {
            return info.nostroAccountId();
        }
        return "ACC-NOSTRO-BANK-B";
    }

    /**
     * Validates whether the bank is a registered and active clearing participant.
     */
    public boolean isBankActive(String bankCode) {
        if (bankCode == null || bankCode.isBlank()) {
            return false;
        }
        BankInfo info = staticRegistry.get(bankCode.trim().toUpperCase());
        return info != null && info.isActive();
    }

    /**
     * Returns the full legal name of the bank for reporting and statements.
     */
    public String getBankName(String bankCode) {
        if (bankCode == null || bankCode.isBlank()) {
            return "Unknown Partner Bank";
        }
        BankInfo info = staticRegistry.get(bankCode.trim().toUpperCase());
        return info != null ? info.bankName() : bankCode.trim().toUpperCase();
    }

    /**
     * Retrieves all active participant banks for directory listing and UI selectors.
     */
    public List<BankInfo> getParticipantBanks() {
        return new ArrayList<>(staticRegistry.values());
    }
}
