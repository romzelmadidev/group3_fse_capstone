package com.bank.cbs.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "participant_banks", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParticipantBankMaster {

    @Id
    @Column(name = "bank_code", length = 32)
    private String bankCode;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "bic_swift_code", length = 11)
    private String bicSwiftCode;

    @Column(name = "clearing_network", length = 20)
    @Builder.Default
    private String clearingNetwork = "BILATERAL";

    @Column(name = "nostro_account_id", nullable = false, length = 64)
    private String nostroAccountId;

    @Column(name = "api_endpoint_url", length = 255)
    private String apiEndpointUrl;

    @Column(name = "api_key", length = 128)
    private String apiKey;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Integer isActive = 1;

    @Column(name = "settlement_currency", length = 3)
    @Builder.Default
    private String settlementCurrency = "PHP";

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
