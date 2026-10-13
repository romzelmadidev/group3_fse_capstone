#!/usr/bin/env bash
# ==============================================================================
# STEP 1: Provision Azure Cloud Infrastructure (Runtime: ~3-5 mins)
# Idempotent: Can be re-run safely at any time. Saves state to azure_deployment_env.sh
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "===================================================================="
echo " STEP 1: PROVISION AZURE CLOUD INFRASTRUCTURE"
echo "===================================================================="

# Load existing environment if previously generated
ENV_FILE="${SCRIPT_DIR}/azure_deployment_env.sh"
if [ -f "$ENV_FILE" ]; then
  echo "Loading existing configuration from $ENV_FILE..."
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

LOCATION="${LOCATION:-${AZURE_LOCATION:-southeastasia}}"
TS=$(date +%s)
RANDOM_SUFFIX="${RANDOM_SUFFIX:-${TS: -5}}"

# Auto-detect Sandbox Resource Group
RESOURCE_GROUP="${RESOURCE_GROUP:-}"
if [ -z "$RESOURCE_GROUP" ]; then
  DETECTED_RG=$(az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name, 'cloud-shell-storage')].name | [0]" -o tsv 2>/dev/null || true)
  if [ -n "$DETECTED_RG" ] && [ "$DETECTED_RG" != "None" ]; then
    RESOURCE_GROUP="$DETECTED_RG"
    echo "Detected assigned Sandbox Resource Group: $RESOURCE_GROUP"
    DETECTED_LOC=$(az group show --name "$RESOURCE_GROUP" --query location -o tsv 2>/dev/null || true)
    if [ -n "$DETECTED_LOC" ] && [ "$DETECTED_LOC" != "None" ]; then
      LOCATION="$DETECTED_LOC"
    fi
  else
    RESOURCE_GROUP="rg-banking-budget-$LOCATION"
    echo "Creating Resource Group: $RESOURCE_GROUP ($LOCATION)..."
    az group create --name "$RESOURCE_GROUP" --location "$LOCATION" -o table
  fi
fi

# Detect existing ACR
EXISTING_ACR=$(az acr list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_ACR" ] && [ "$EXISTING_ACR" != "None" ]; then
  ACR_NAME="$EXISTING_ACR"
  echo "Detected existing ACR: $ACR_NAME"
else
  ACR_NAME="${ACR_NAME:-acrbanking$RANDOM_SUFFIX}"
fi

# Detect existing AKS
EXISTING_AKS=$(az aks list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_AKS" ] && [ "$EXISTING_AKS" != "None" ]; then
  AKS_CLUSTER_NAME="$EXISTING_AKS"
  echo "Detected existing AKS Cluster: $AKS_CLUSTER_NAME"
else
  AKS_CLUSTER_NAME="${AKS_CLUSTER_NAME:-aks-banking-budget}"
fi

# Detect existing SQL
EXISTING_SQL=$(az sql server list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_SQL" ] && [ "$EXISTING_SQL" != "None" ]; then
  SQL_SERVER_NAME="$EXISTING_SQL"
  echo "Detected existing Azure SQL Server: $SQL_SERVER_NAME"
else
  SQL_SERVER_NAME="${SQL_SERVER_NAME:-sql-banking-$RANDOM_SUFFIX}"
fi

SQL_DB_NAME="sqldb-master"
SQL_ADMIN_USER="sqladminuser"
SQL_ADMIN_PASS="${SQL_ADMIN_PASS:-Capst0ne!Secure${RANDOM_SUFFIX}#}"

PG_SERVER_NAME="${PG_SERVER_NAME:-psql-banking-$RANDOM_SUFFIX}"
PG_DB_NAME="banking_audit"
PG_ADMIN_USER="audit_admin"
PG_ADMIN_PASS="${PG_ADMIN_PASS:-Audit!Vault${RANDOM_SUFFIX}#}"

JWT_SECRET="c3VwZXItc2VjcmV0LWtleS1mb3ItZnNlLWNhcHN0b25lLWJhbmtpbmctcGxhdGZvcm0tMjAyNi0xMjM0NTY3ODkwMTI="
DATADOG_API_KEY="${DATADOG_API_KEY:-00000000000000000000000000000000}"

# 1. Provision Azure SQL
echo ""
echo "[1/4] Configuring Azure SQL Server & Database..."
if az sql server show --name "$SQL_SERVER_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing Azure SQL Server: $SQL_SERVER_NAME..."
  az sql server update --name "$SQL_SERVER_NAME" --resource-group "$RESOURCE_GROUP" --admin-password "$SQL_ADMIN_PASS" -o none 2>/dev/null || true
else
  echo "Provisioning Azure SQL Logical Server & Basic 5 DTU Database (~$0.16/day)..."
  az sql server create \
    --name "$SQL_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --admin-user "$SQL_ADMIN_USER" \
    --admin-password "$SQL_ADMIN_PASS" \
    -o table

  az sql server firewall-rule create \
    --server "$SQL_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --name "AllowAzureInternal" \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 0.0.0.0 \
    -o table

  az sql db create \
    --server "$SQL_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --name "$SQL_DB_NAME" \
    --edition Basic \
    --service-objective Basic \
    --capacity 5 \
    --backup-storage-redundancy Local \
    --yes \
    -o table
fi

SQL_FQDN="${SQL_SERVER_NAME}.database.windows.net"
SQL_JDBC_URL="jdbc:sqlserver://${SQL_FQDN}:1433;databaseName=${SQL_DB_NAME};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"
PG_FQDN="postgres.banking.svc.cluster.local"
PG_JDBC_URL="jdbc:postgresql://${PG_FQDN}:5432/${PG_DB_NAME}?sslmode=disable"

# 2. Provision Storage Account
echo ""
echo "[2/4] Configuring Azure Storage Account for Compliance Vault..."
EXISTING_SA=$(az storage account list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_SA" ] && [ "$EXISTING_SA" != "None" ]; then
  STORAGE_ACCOUNT_NAME="$EXISTING_SA"
  echo "Using existing Storage Account: $STORAGE_ACCOUNT_NAME..."
else
  STORAGE_ACCOUNT_NAME="${STORAGE_ACCOUNT_NAME:-stbanking${RANDOM_SUFFIX}}"
  az storage account create \
    --name "$STORAGE_ACCOUNT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --sku Standard_LRS \
    --kind StorageV2 \
    -o table
fi
STORAGE_CONN_STR=$(az storage account show-connection-string --name "$STORAGE_ACCOUNT_NAME" --resource-group "$RESOURCE_GROUP" --query "connectionString" -o tsv)
az storage container create --name "compliance-vault" --connection-string "$STORAGE_CONN_STR" 2>/dev/null || true

# 3. Provision ACR
echo ""
echo "[3/4] Configuring Azure Container Registry..."
if az acr show --name "$ACR_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing ACR: $ACR_NAME..."
else
  az acr create \
    --name "$ACR_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --sku Basic \
    --admin-enabled true \
    -o table
fi

# 4. Provision Single-Node AKS Cluster
echo ""
echo "[4/4] Configuring Single-Node AKS Cluster..."
if az aks show --name "$AKS_CLUSTER_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing AKS Cluster: $AKS_CLUSTER_NAME..."
else
  az provider register --namespace Microsoft.ContainerService 2>/dev/null || true
  CREATED=false
  for VM_SIZE in "${AKS_VM_SIZE:-Standard_B2s}" "Standard_B2s" "Standard_D2s_v3" "Standard_DS2_v2"; do
    echo "Attempting AKS creation with VM size: $VM_SIZE..."
    if az aks create \
      --name "$AKS_CLUSTER_NAME" \
      --resource-group "$RESOURCE_GROUP" \
      --location "$LOCATION" \
      --node-count 1 \
      --node-vm-size "$VM_SIZE" \
      --generate-ssh-keys \
      -o table; then
      CREATED=true
      echo "AKS Cluster $AKS_CLUSTER_NAME created successfully with $VM_SIZE."
      break
    else
      echo "[NOTICE] VM size $VM_SIZE restricted in sandbox. Retrying next candidate..."
    fi
  done

  if [ "$CREATED" != "true" ]; then
    echo "Error: Failed to create AKS cluster in resource group $RESOURCE_GROUP."
    exit 1
  fi
fi

az aks update -n "$AKS_CLUSTER_NAME" -g "$RESOURCE_GROUP" --attach-acr "$ACR_NAME" 2>/dev/null || true

# Save authoritative configuration to environment file for subsequent steps
cat <<EOF > "$ENV_FILE"
# Auto-generated by step1_infra_provision.sh - $(date)
export RESOURCE_GROUP="$RESOURCE_GROUP"
export LOCATION="$LOCATION"
export RANDOM_SUFFIX="$RANDOM_SUFFIX"
export ACR_NAME="$ACR_NAME"
export AKS_CLUSTER_NAME="$AKS_CLUSTER_NAME"
export SQL_SERVER_NAME="$SQL_SERVER_NAME"
export SQL_DB_NAME="$SQL_DB_NAME"
export SQL_ADMIN_USER="$SQL_ADMIN_USER"
export SQL_ADMIN_PASS="$SQL_ADMIN_PASS"
export SQL_FQDN="$SQL_FQDN"
export SQL_JDBC_URL="$SQL_JDBC_URL"
export PG_FQDN="$PG_FQDN"
export PG_JDBC_URL="$PG_JDBC_URL"
export PG_ADMIN_USER="$PG_ADMIN_USER"
export PG_ADMIN_PASS="$PG_ADMIN_PASS"
export STORAGE_ACCOUNT_NAME="$STORAGE_ACCOUNT_NAME"
export STORAGE_CONN_STR="$STORAGE_CONN_STR"
export JWT_SECRET="$JWT_SECRET"
export DATADOG_API_KEY="$DATADOG_API_KEY"
EOF

chmod +x "$ENV_FILE"

echo ""
echo "===================================================================="
echo " STEP 1 COMPLETE!"
echo " Configuration saved to: $ENV_FILE"
echo " Next step: Run 'bash infrastructure/azure/step2a_build_backend.sh'"
echo "===================================================================="
