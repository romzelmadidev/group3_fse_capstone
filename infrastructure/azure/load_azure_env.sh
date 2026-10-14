#!/usr/bin/env bash
# ==============================================================================
# AuraBank: Autonomous Session-Agnostic Azure Environment Resolver
# Auto-discovers Azure resources across ANY session, terminal tab, or reconnect.
# ==============================================================================

_RESOLVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1. Search for existing state files across common persistent locations
_ENV_FILE=""
if [ -f "${_RESOLVER_DIR}/azure_deployment_env.sh" ]; then
  _ENV_FILE="${_RESOLVER_DIR}/azure_deployment_env.sh"
elif [ -f "${HOME:-}/azure_deployment_env.sh" ]; then
  _ENV_FILE="${HOME}/azure_deployment_env.sh"
elif [ -f "${HOME:-}/clouddrive/azure_deployment_env.sh" ]; then
  _ENV_FILE="${HOME}/clouddrive/azure_deployment_env.sh"
fi

if [ -n "$_ENV_FILE" ] && [ -f "$_ENV_FILE" ]; then
  # shellcheck source=/dev/null
  source "$_ENV_FILE"
fi

# 2. Dynamic Discovery via Azure CLI if any core variable is still unset
if command -v az &>/dev/null; then
  # 2a. Resource Group & Location
  if [ -z "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_RG=$(az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name, 'cloud-shell-storage')].name | [0]" -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_RG" ] && [ "$_DETECTED_RG" != "None" ]; then
      export RESOURCE_GROUP="$_DETECTED_RG"
    else
      export RESOURCE_GROUP="rg-banking-budget-${AZURE_LOCATION:-southeastasia}"
    fi
  fi

  if [ -z "${LOCATION:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_LOC=$(az group show --name "$RESOURCE_GROUP" --query location -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_LOC" ] && [ "$_DETECTED_LOC" != "None" ]; then
      export LOCATION="$_DETECTED_LOC"
    else
      export LOCATION="${AZURE_LOCATION:-southeastasia}"
    fi
  fi

  # 2b. Azure Container Registry (ACR)
  if [ -z "${ACR_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_ACR=$(az acr list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_ACR" ] && [ "$_DETECTED_ACR" != "None" ]; then
      export ACR_NAME="$_DETECTED_ACR"
    fi
  fi

  # 2c. Azure Kubernetes Service (AKS)
  if [ -z "${AKS_CLUSTER_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_AKS=$(az aks list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_AKS" ] && [ "$_DETECTED_AKS" != "None" ]; then
      export AKS_CLUSTER_NAME="$_DETECTED_AKS"
    else
      export AKS_CLUSTER_NAME="aks-banking-budget"
    fi
  fi

  # 2d. Azure SQL Server & Database
  if [ -z "${SQL_SERVER_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_SQL=$(az sql server list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_SQL" ] && [ "$_DETECTED_SQL" != "None" ]; then
      export SQL_SERVER_NAME="$_DETECTED_SQL"
    fi
  fi
  export SQL_DB_NAME="${SQL_DB_NAME:-sqldb-master}"
  export SQL_ADMIN_USER="${SQL_ADMIN_USER:-sqladmin}"
  export SQL_ADMIN_PASS="${SQL_ADMIN_PASS:-AuraBank2026!SecureKey}"

  if [ -n "${SQL_SERVER_NAME:-}" ]; then
    export SQL_FQDN="${SQL_SERVER_NAME}.database.windows.net"
    export SQL_JDBC_URL="jdbc:sqlserver://${SQL_FQDN}:1433;databaseName=${SQL_DB_NAME};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"
  fi

  # 2e. Azure Storage Account
  if [ -z "${STORAGE_ACCOUNT_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _DETECTED_ST=$(az storage account list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
    if [ -n "$_DETECTED_ST" ] && [ "$_DETECTED_ST" != "None" ]; then
      export STORAGE_ACCOUNT_NAME="$_DETECTED_ST"
    fi
  fi

  if [ -z "${STORAGE_CONN_STR:-}" ] && [ -n "${STORAGE_ACCOUNT_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    _CONN=$(az storage account show-connection-string --resource-group "$RESOURCE_GROUP" --name "$STORAGE_ACCOUNT_NAME" --query connectionString -o tsv 2>/dev/null || true)
    if [ -n "$_CONN" ] && [ "$_CONN" != "None" ]; then
      export STORAGE_CONN_STR="$_CONN"
    fi
  fi
fi

# 2f. Common App Secrets & Defaults
export PG_ADMIN_USER="${PG_ADMIN_USER:-aurabank}"
export PG_ADMIN_PASS="${PG_ADMIN_PASS:-AuraBank2026!Audit}"
export PG_DB_NAME="${PG_DB_NAME:-aurabank_audit}"
export PG_JDBC_URL="${PG_JDBC_URL:-jdbc:postgresql://postgres-audit-vault.banking.svc.cluster.local:5432/${PG_DB_NAME}?sslmode=disable}"
export JWT_SECRET="${JWT_SECRET:-dGhpcy1pcy1hLXZlcnktc2VjdXJlLWFuZC1sb25nLXNlY3JldC1rZXktZm9yLWF1cmFiYW5rLTIwMjYtY2Fwc3RvbmU=}"
export DATADOG_API_KEY="${DATADOG_API_KEY:-00000000000000000000000000000000}"
export DATADOG_SITE="${DATADOG_SITE:-datadoghq.com}"
export INTERBANK_API_KEY="${INTERBANK_API_KEY:-${PARTNER_BANK_API_KEY:-AURABANK-SECURE-KEY-2026}}"
export PARTNER_BANK_URL="${PARTNER_BANK_URL:-http://transfer-orchestrator.banking.svc.cluster.local:8082/api/v1/mock-partner/transfers}"

# 3. Cache discovered state to disk for lightning-fast future loads
mkdir -p "${_RESOLVER_DIR}"
cat <<EOF > "${_RESOLVER_DIR}/azure_deployment_env.sh"
# Auto-generated AuraBank Deployment Environment (Cached)
export RESOURCE_GROUP="${RESOURCE_GROUP:-}"
export LOCATION="${LOCATION:-}"
export ACR_NAME="${ACR_NAME:-}"
export AKS_CLUSTER_NAME="${AKS_CLUSTER_NAME:-}"
export SQL_SERVER_NAME="${SQL_SERVER_NAME:-}"
export SQL_DB_NAME="${SQL_DB_NAME:-}"
export SQL_ADMIN_USER="${SQL_ADMIN_USER:-}"
export SQL_ADMIN_PASS="${SQL_ADMIN_PASS:-}"
export SQL_JDBC_URL="${SQL_JDBC_URL:-}"
export STORAGE_ACCOUNT_NAME="${STORAGE_ACCOUNT_NAME:-}"
export STORAGE_CONN_STR="${STORAGE_CONN_STR:-}"
export PG_ADMIN_USER="${PG_ADMIN_USER:-}"
export PG_ADMIN_PASS="${PG_ADMIN_PASS:-}"
export PG_DB_NAME="${PG_DB_NAME:-}"
export PG_JDBC_URL="${PG_JDBC_URL:-}"
export JWT_SECRET="${JWT_SECRET:-}"
export DATADOG_API_KEY="${DATADOG_API_KEY:-}"
export DATADOG_SITE="${DATADOG_SITE:-}"
export INTERBANK_API_KEY="${INTERBANK_API_KEY:-}"
export PARTNER_BANK_URL="${PARTNER_BANK_URL:-}"
EOF

# Also persist in Cloud Shell home directory if available
if [ -n "${HOME:-}" ] && [ -d "${HOME}" ]; then
  cp "${_RESOLVER_DIR}/azure_deployment_env.sh" "${HOME}/azure_deployment_env.sh" 2>/dev/null || true
  if [ -d "${HOME}/clouddrive" ]; then
    cp "${_RESOLVER_DIR}/azure_deployment_env.sh" "${HOME}/clouddrive/azure_deployment_env.sh" 2>/dev/null || true
  fi
fi
