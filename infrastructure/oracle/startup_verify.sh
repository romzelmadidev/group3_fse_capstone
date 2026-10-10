#!/usr/bin/env bash
# ==============================================================================
# Oracle XE Master: Container Startup Verification & Auto-Healing Hook
# Executed by gvenzl/oracle-xe on EVERY container boot via
# /container-entrypoint-startdb.d/startup_verify.sh
# ==============================================================================

echo "================================================================================"
echo "CONTAINER: [startup_verify.sh] Checking Oracle XE schema and seed status..."
echo "================================================================================"

# Query table and user counts from XEPDB1 pluggable database
STATUS_OUTPUT=$(sqlplus -s / as sysdba << "EOF"
SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
ALTER SESSION SET CONTAINER = XEPDB1;

SELECT 'TBL_CNT=' || COUNT(*) FROM all_tables WHERE owner = 'FSE_USER' AND table_name IN ('USERS', 'ACCOUNTS', 'BALANCE_MASTER', 'TRANSACTIONS', 'GL_ACCOUNTS', 'SYSTEM_DATES');
SELECT 'USR_CNT=' || COUNT(*) FROM fse_user.users;
SELECT 'ACC_CNT=' || COUNT(*) FROM fse_user.accounts;
SELECT 'GL_CNT='  || COUNT(*) FROM fse_user.gl_accounts;
SELECT 'SYN_CNT=' || COUNT(*) FROM all_synonyms WHERE owner = 'CORE' AND table_name = 'TRANSACTIONS';
EXIT;
EOF
)

TBL_CNT=$(echo "$STATUS_OUTPUT" | grep 'TBL_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
USR_CNT=$(echo "$STATUS_OUTPUT" | grep 'USR_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
ACC_CNT=$(echo "$STATUS_OUTPUT" | grep 'ACC_CNT=' | cut -d'=' -f2 | tr -dc '0-9')
GL_CNT=$(echo "$STATUS_OUTPUT"  | grep 'GL_CNT='  | cut -d'=' -f2 | tr -dc '0-9')
SYN_CNT=$(echo "$STATUS_OUTPUT" | grep 'SYN_CNT=' | cut -d'=' -f2 | tr -dc '0-9')

TBL_CNT=${TBL_CNT:-0}
USR_CNT=${USR_CNT:-0}
ACC_CNT=${ACC_CNT:-0}
GL_CNT=${GL_CNT:-0}
SYN_CNT=${SYN_CNT:-0}

NEED_INIT=false

if [ "$FORCE_DB_INIT" = "true" ] || [ "$FORCE_REINIT" = "true" ]; then
    echo "CONTAINER: [startup_verify.sh] FORCE_DB_INIT flag detected. Triggering re-initialization..."
    NEED_INIT=true
elif [ "$TBL_CNT" -lt 6 ]; then
    echo "CONTAINER: [startup_verify.sh] Missing core tables (found $TBL_CNT/6). Triggering init.sql..."
    NEED_INIT=true
elif [ "$USR_CNT" -eq 0 ] || [ "$ACC_CNT" -eq 0 ] || [ "$GL_CNT" -eq 0 ]; then
    echo "CONTAINER: [startup_verify.sh] Database missing seed records (Users: $USR_CNT, Accounts: $ACC_CNT, GL: $GL_CNT). Triggering init.sql..."
    NEED_INIT=true
elif [ "$SYN_CNT" -eq 0 ]; then
    echo "CONTAINER: [startup_verify.sh] Missing CORE schema synonyms. Triggering init.sql..."
    NEED_INIT=true
fi

if [ "$NEED_INIT" = "true" ]; then
    echo "CONTAINER: [startup_verify.sh] Executing /container-entrypoint-initdb.d/init.sql..."
    echo "exit" | sqlplus -s / as sysdba @/container-entrypoint-initdb.d/init.sql
    EXIT_CODE=$?
    if [ $EXIT_CODE -eq 0 ]; then
        echo "================================================================================"
        echo "CONTAINER: [startup_verify.sh] Database initialization & seeding completed successfully!"
        echo "================================================================================"
    else
        echo "================================================================================"
        echo "CONTAINER: [startup_verify.sh] ERROR: init.sql exited with code $EXIT_CODE!"
        echo "================================================================================"
    fi
else
    echo "================================================================================"
    echo "CONTAINER: [startup_verify.sh] Oracle XE Master is verified HEALTHY & SEEDED."
    echo "  - Core Tables: Present ($TBL_CNT/6 verified)"
    echo "  - Users:       $USR_CNT seeded"
    echo "  - Accounts:    $ACC_CNT seeded"
    echo "  - GL Accounts: $GL_CNT seeded"
    echo "  - Synonyms:    CORE schema synchronized"
    echo "================================================================================"
fi
