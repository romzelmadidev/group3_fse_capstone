package com.bank.compliance.generator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
public class AmlaCtrGenerator {

    public String generateCtrXml(String transactionId, String sourceAccountId, String destinationAccountId,
                                 BigDecimal amount, String currency, Instant executedAt) {
        String amlcRef = "AMLC-CTR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <CoveredTransactionReport xmlns="http://www.amlc.gov.ph/schema/ctr/v2">
                    <Header>
                        <BatchReference>%s</BatchReference>
                        <ReportingInstitution>
                            <InstitutionCode>BANK001</InstitutionCode>
                            <InstitutionName>Universal Retail Bank</InstitutionName>
                        </ReportingInstitution>
                        <ReportDate>%s</ReportDate>
                        <TotalTransactions>1</TotalTransactions>
                    </Header>
                    <Transactions>
                        <Transaction>
                            <TransactionId>%s</TransactionId>
                            <TransactionType>FUNDS_TRANSFER</TransactionType>
                            <ExecutionTimestamp>%s</ExecutionTimestamp>
                            <Amount currency="%s">%s</Amount>
                            <Originator>
                                <AccountId>%s</AccountId>
                            </Originator>
                            <Beneficiary>
                                <AccountId>%s</AccountId>
                            </Beneficiary>
                            <ComplianceThreshold>500000.00</ComplianceThreshold>
                            <Status>SUBMITTED_TO_AMLC</Status>
                        </Transaction>
                    </Transactions>
                </CoveredTransactionReport>
                """.trim(),
                amlcRef,
                Instant.now().toString(),
                transactionId,
                executedAt != null ? executedAt.toString() : Instant.now().toString(),
                currency != null ? currency : "PHP",
                amount.toPlainString(),
                sourceAccountId,
                destinationAccountId
        );
    }
}
