#!/usr/bin/env bash
# ==============================================================================
# Oracle XE Master: Container Startup Verification Hook
# Executed by gvenzl/oracle-xe on container start via
# /container-entrypoint-startdb.d/startup_verify.sh
# ==============================================================================
# Pure non-destructive status inspection.
# Schema migrations & auto-healing are authoritatively handled by Flyway.
# ==============================================================================

echo "================================================================================"
echo "CONTAINER: [startup_verify.sh] Checking Oracle XE schema and seed status..."
echo "================================================================================"

# Query table and user counts from XEPDB1 pluggable database
STATUS_OUTPUT=$(sqlplus -s / as sysdba << "EOF"
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
ALTER SESSION SET CONTAINER = XEPDB1;

SELECT 'TBL_CNT=' || COUNT(*) FROM all_tables WHERE owner = 'FSE_USER' AND table_name IN ('USERS', 'ACCOUNTS', 'BALANCE_MASTER', 'TRANSACTIONS', 'GL_ACCOUNTS', 'SYSTEM_DATES');
SELECT 'ADMIN_TBL=' || COUNT(*) FROM all_tables WHERE owner = 'FSE_USER' AND table_name = 'ADMIN_USERS';
SELECT 'FLYWAY_TBL=' || COUNT(*) FROM all_tables WHERE owner = 'FSE_USER' AND table_name = 'FLYWAY_SCHEMA_HISTORY';
SELECT 'USR_CNT=' || COUNT(*) FROM fse_user.users;
SELECT 'ADMIN_CNT=' || COUNT(*) FROM fse_user.admin_users;
SELECT 'ACC_CNT=' || COUNT(*) FROM fse_user.accounts;
SELECT 'GL_CNT='  || COUNT(*) FROM fse_user.gl_accounts;
SELECT 'SYN_CNT=' || COUNT(*) FROM all_synonyms WHERE owner = 'CORE' AND table_name = 'TRANSACTIONS';
EXIT;
EOF
)

TBL_CNT=$(echo "$STATUS_OUTPUT" | grep 'TBL_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
ADMIN_TBL=$(echo "$STATUS_OUTPUT" | grep 'ADMIN_TBL=' | cut -d'=' -f2 | tr -dc '0-9')
FLYWAY_TBL=$(echo "$STATUS_OUTPUT" | grep 'FLYWAY_TBL=' | cut -d'=' -f2 | tr -dc '0-9')
USR_CNT=$(echo "$STATUS_OUTPUT" | grep 'USR_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
ADMIN_CNT=$(echo "$STATUS_OUTPUT" | grep 'ADMIN_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
ACC_CNT=$(echo "$STATUS_OUTPUT" | grep 'ACC_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
GL_CNT=$(echo "$STATUS_OUTPUT"  | grep 'GL_CNT='  | cut -d'=' -f2 | tr -dc '0-9')
SYN_CNT=$(echo "$STATUS_OUTPUT" | grep 'SYN_CNT=' | cut -d'=' -f2 | tr -dc '0-9')

TBL_CNT=${TBL_CNT:-0}
ADMIN_TBL=${ADMIN_TBL:-0}
FLYWAY_TBL=${FLYWAY_TBL:-0}
USR_CNT=${USR_CNT:-0}
ADMIN_CNT=${ADMIN_CNT:-0}
ACC_CNT=${ACC_CNT:-0}
GL_CNT=${GL_CNT:-0}
SYN_CNT=${SYN_CNT:-0}

echo "================================================================================"
echo "CONTAINER: [startup_verify.sh] Oracle XE Master Schema Status:"
echo "  - Core Tables: Present ($TBL_CNT/6 verified)"
echo "  - Admin Users Table: ($ADMIN_TBL/1 verified)"
echo "  - Flyway History Table: ($FLYWAY_TBL/1 verified)"
echo "  - Retail Users: $USR_CNT records"
echo "  - Admin Users:  $ADMIN_CNT records"
echo "  - Accounts:     $ACC_CNT records"
echo "  - GL Accounts:  $GL_CNT records"
echo "  - Synonyms:     CORE schema ($SYN_CNT synonyms)"
echo "  - Schema Migration: Managed authoritatively by Flyway (t24-mock-cbs)"
echo "================================================================================"
exit 0
