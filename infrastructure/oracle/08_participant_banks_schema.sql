-- 08_participant_banks_schema.sql
-- Table: participant_banks and seed for GL Account 1020-NOSTRO-CLEARING

BEGIN
    EXECUTE IMMEDIATE '
    CREATE TABLE participant_banks (
        bank_code           VARCHAR2(32) PRIMARY KEY,
        bank_name           VARCHAR2(100) NOT NULL,
        bic_swift_code      VARCHAR2(11),
        clearing_network    VARCHAR2(20) DEFAULT ''BILATERAL'',
        nostro_account_id   VARCHAR2(64) NOT NULL,
        api_endpoint_url    VARCHAR2(255),
        api_key             VARCHAR2(128),
        is_active           NUMBER(1) DEFAULT 1 NOT NULL,
        settlement_currency VARCHAR2(3) DEFAULT ''PHP'' NOT NULL,
        created_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        updated_at          TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
        CONSTRAINT fk_pb_nostro_acc FOREIGN KEY (nostro_account_id) REFERENCES accounts(account_id)
    )';
    DBMS_OUTPUT.PUT_LINE('Created table participant_banks');
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -955 THEN
            DBMS_OUTPUT.PUT_LINE('Table participant_banks already exists.');
        ELSE
            RAISE;
        END IF;
END;
/

-- Seeds for participant_banks
MERGE INTO participant_banks target
USING (
    SELECT 'COOBS' AS bank_code, 'Cooperative Overseas Banking System (CooBS)' AS bank_name, 'COOBPHM2XXX' AS bic_swift_code, 'BILATERAL' AS clearing_network, 'ACC-NOSTRO-BANK-B' AS nostro_account_id, 'http://localhost:8082/api/v1/mock-partner/transfers' AS api_endpoint_url, 'AURABANK-SECURE-KEY-2026' AS api_key, 1 AS is_active, 'PHP' AS settlement_currency FROM dual
    UNION ALL
    SELECT 'AURABANK' AS bank_code, 'AuraBank Core Retail' AS bank_name, 'AURAPHM1XXX' AS bic_swift_code, 'CORE' AS clearing_network, 'ACC-NOSTRO-BANK-B' AS nostro_account_id, NULL AS api_endpoint_url, NULL AS api_key, 1 AS is_active, 'PHP' AS settlement_currency FROM dual
) src
ON (target.bank_code = src.bank_code)
WHEN MATCHED THEN
    UPDATE SET target.bank_name = src.bank_name,
               target.nostro_account_id = src.nostro_account_id,
               target.api_endpoint_url = src.api_endpoint_url,
               target.is_active = src.is_active,
               target.updated_at = CURRENT_TIMESTAMP
WHEN NOT MATCHED THEN
    INSERT (bank_code, bank_name, bic_swift_code, clearing_network, nostro_account_id, api_endpoint_url, api_key, is_active, settlement_currency, created_at, updated_at)
    VALUES (src.bank_code, src.bank_name, src.bic_swift_code, src.clearing_network, src.nostro_account_id, src.api_endpoint_url, src.api_key, src.is_active, src.settlement_currency, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Seeds for gl_accounts: 1020-NOSTRO-CLEARING
MERGE INTO gl_accounts target
USING (
    SELECT '1020-NOSTRO-CLEARING' AS gl_code, 'Interbank Nostro Settlement Buffer (CooBS)' AS account_name, 'ASSET' AS account_type, 'PHP' AS currency, 1 AS is_active FROM dual
) src
ON (target.gl_code = src.gl_code)
WHEN MATCHED THEN
    UPDATE SET target.account_name = src.account_name, target.account_type = src.account_type, target.is_active = src.is_active
WHEN NOT MATCHED THEN
    INSERT (gl_code, account_name, account_type, currency, is_active)
    VALUES (src.gl_code, src.account_name, src.account_type, src.currency, src.is_active);

-- Seeds for gl_balances for 1020-NOSTRO-CLEARING
MERGE INTO gl_balances target
USING (
    SELECT '1020-NOSTRO-CLEARING' AS gl_code, '2026-M10' AS fiscal_period FROM dual
) src
ON (target.gl_code = src.gl_code AND target.fiscal_period = src.fiscal_period)
WHEN NOT MATCHED THEN
    INSERT (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
    VALUES (src.gl_code, src.fiscal_period, 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

MERGE INTO gl_balances target
USING (
    SELECT '1020-NOSTRO-CLEARING' AS gl_code, '2026-10' AS fiscal_period FROM dual
) src
ON (target.gl_code = src.gl_code AND target.fiscal_period = src.fiscal_period)
WHEN NOT MATCHED THEN
    INSERT (gl_code, fiscal_period, total_debit, total_credit, net_balance, updated_at)
    VALUES (src.gl_code, src.fiscal_period, 0.0000, 0.0000, 0.0000, CURRENT_TIMESTAMP);

COMMIT;
