package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "compliance_filings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceFiling {

    @Id
    @Column(name = "filing_id", length = 64)
    private String filingId;

    @Column(name = "filing_type", nullable = false, length = 30)
    private String filingType; // CTR_500K, STR_FRAUD

    @Column(name = "business_date", nullable = false, length = 20)
    private String businessDate;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Lob
    @Column(name = "payload_xml", nullable = false)
    private String payloadXml;

    @Column(name = "amlc_ref", nullable = false, length = 64)
    private String amlcRef;

    @Column(name = "filed_at", nullable = false)
    @Builder.Default
    private Instant filedAt = Instant.now();
}
