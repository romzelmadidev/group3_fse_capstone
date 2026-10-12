package com.bank.orchestrator.service;

import com.bank.ledger.contracts.dto.ParticipantBankDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves participant bank routing, Nostro clearing buffers, and endpoint URLs.
 * Synchronizes with T24 CBS via Temenos OFS protocol and exposes a public facade.
 * Enforces a strict 3-tier priority hierarchy for dispatch endpoints:
 * 1. Environment Variable Override (PARTNER_BANK_URL / COOBS_API_ENDPOINT_URL / BANK_<CODE>_URL)
 * 2. CBS Core Directory Configuration (via OFS)
 * 3. Default Local Simulator Fallback
 */
@Service
public class BankRoutingService {

    private static final Logger log = LoggerFactory.getLogger(BankRoutingService.class);

    private final Environment environment;
    private final CbsClientService cbsClientService;
    private final String defaultPartnerUrl;
    private final Map<String, BankInfo> staticRegistry = new ConcurrentHashMap<>();

    public record BankInfo(
            String bankCode,
            String bankName,
            String bicSwiftCode,
            String clearingNetwork,
            String nostroAccountId,
            String apiEndpointUrl,
            boolean isActive
    ) {
        public String defaultApiUrl() {
            return apiEndpointUrl;
        }
    }

    public BankRoutingService(
            Environment environment,
            @Value("${services.partner.url:http://localhost:8082/api/v1/mock-partner/transfers}") String defaultPartnerUrl) {
        this(environment, null, defaultPartnerUrl);
    }

    @Autowired
    public BankRoutingService(
            Environment environment,
            @Autowired(required = false) CbsClientService cbsClientService,
            @Value("${services.partner.url:http://localhost:8082/api/v1/mock-partner/transfers}") String defaultPartnerUrl) {
        this.environment = environment;
        this.cbsClientService = cbsClientService;
        String fallback = (defaultPartnerUrl != null && !defaultPartnerUrl.isBlank())
                ? defaultPartnerUrl.trim()
                : "http://transfer-orchestrator:8082/api/v1/mock-partner/transfers";
        this.defaultPartnerUrl = fallback;

        // Baseline participants
        staticRegistry.put("COOBS", new BankInfo(
                "COOBS",
                "Cooperative Overseas Banking System (CooBS)",
                "COOBPHM2XXX",
                "BILATERAL",
                "ACC-NOSTRO-BANK-B",
                fallback,
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

    @PostConstruct
    public void init() {
        try {
            refreshFromCbs();
        } catch (Exception e) {
            log.info("Initial CBS participant banks OFS synchronization deferred: {}", e.getMessage());
        }
    }

    /**
     * Refreshes the internal participant directory from T24 CBS using Temenos OFS protocol.
     */
    public synchronized void refreshFromCbs() {
        if (cbsClientService == null) {
            return;
        }
        try {
            List<ParticipantBankDto> cbsBanks = cbsClientService.getParticipantBanks();
            if (cbsBanks != null && !cbsBanks.isEmpty()) {
                for (ParticipantBankDto dto : cbsBanks) {
                    if (dto.getBankCode() != null && !dto.getBankCode().isBlank()) {
                        String code = dto.getBankCode().trim().toUpperCase();
                        boolean active = (dto.getIsActive() == null || dto.getIsActive() == 1);
                        staticRegistry.put(code, new BankInfo(
                                code,
                                dto.getBankName() != null ? dto.getBankName() : code,
                                dto.getBicSwiftCode(),
                                dto.getClearingNetwork() != null ? dto.getClearingNetwork() : "BILATERAL",
                                dto.getNostroAccountId() != null ? dto.getNostroAccountId() : "ACC-NOSTRO-BANK-B",
                                dto.getApiEndpointUrl(),
                                active
                        ));
                    }
                }
                log.info("Refreshed {} participant banks from T24 CBS via OFS", cbsBanks.size());
            }
        } catch (Exception e) {
            log.warn("Could not synchronize participant banks from CBS via OFS: {}", e.getMessage());
        }
    }

    /**
     * Resolves the effective HTTP endpoint URL for dispatching an outward wire.
     * Tier 1: Specific env vars (e.g. COOBS_API_ENDPOINT_URL, PARTNER_BANK_URL, BANK_COOBS_URL).
     * Tier 2: Dynamic / registered bank endpoint from T24 CBS.
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

        // 2. Check static/CBS registry
        BankInfo info = staticRegistry.get(normalized);
        if (info != null && info.apiEndpointUrl() != null && !info.apiEndpointUrl().isBlank()) {
            return info.apiEndpointUrl().trim();
        }

        // 3. Fallback
        if (defaultPartnerUrl != null && !defaultPartnerUrl.isBlank()) {
            return defaultPartnerUrl;
        }
        return "http://transfer-orchestrator:8082/api/v1/mock-partner/transfers";
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
     * Acts as an enterprise facade over the internal T24 OFS protocol.
     */
    public List<BankInfo> getParticipantBanks() {
        refreshFromCbs();
        List<BankInfo> result = new ArrayList<>();
        for (BankInfo b : staticRegistry.values()) {
            String effectiveUrl = resolveEffectiveEndpointUrl(b.bankCode());
            result.add(new BankInfo(
                    b.bankCode(),
                    b.bankName(),
                    b.bicSwiftCode(),
                    b.clearingNetwork(),
                    b.nostroAccountId(),
                    effectiveUrl,
                    b.isActive()
            ));
        }
        return result;
    }
}
