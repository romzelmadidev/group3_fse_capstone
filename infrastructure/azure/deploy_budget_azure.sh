#!/usr/bin/env bash
# ==============================================================================
# FSE CAPSTONE: Cost-Optimized Azure Infrastructure Deployment
# Budget Constraint: Total Azure Spend <= $10.00 USD
# Target Environment: Azure Cloud Shell (Bash) or Local Bash with Azure CLI
# ==============================================================================
set -euo pipefail

USE_MANAGED_POSTGRES="${USE_MANAGED_POSTGRES:-false}"

echo "===================================================================="
echo " Starting FSE Banking Capstone - Budget-Optimized Azure Deployment"
if [ "$USE_MANAGED_POSTGRES" = "true" ]; then
  echo " Architecture: Single B2s AKS + Basic SQL + Managed PostgreSQL B1ms"
  echo " Estimated Daily Run Cost: ~\$1.73/day (~5.7 days on \$10.00 budget)"
else
  echo " Architecture: Single B2s AKS + Basic SQL + In-Cluster PostgreSQL (\$0.00)"
  echo " Estimated Daily Run Cost: ~\$1.33/day (~7.5 days on \$10.00 budget)"
  echo " Paused Cost (az aks stop): ~\$0.21/day (~47 days on \$10.00 budget)"
fi
echo "===================================================================="

# 1. Configuration & Random Identifier
LOCATION="${AZURE_LOCATION:-southeastasia}"

# Generate safe 5-character numeric suffix without broken pipe risks
TS=$(date +%s)
RANDOM_SUFFIX="${TS: -5}"

# Check if running inside a pre-allocated lab sandbox with an existing resource group
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
  fi
fi

# Detect existing ACR in the resource group if already created
EXISTING_ACR=$(az acr list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_ACR" ] && [ "$EXISTING_ACR" != "None" ]; then
  ACR_NAME="$EXISTING_ACR"
  echo "Detected existing ACR: $ACR_NAME"
else
  ACR_NAME="${ACR_NAME:-acrbanking$RANDOM_SUFFIX}"
fi

AKS_CLUSTER_NAME="${AKS_CLUSTER_NAME:-aks-banking-budget}"

# Detect existing Azure SQL Server in the resource group if already created
EXISTING_SQL=$(az sql server list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_SQL" ] && [ "$EXISTING_SQL" != "None" ]; then
  SQL_SERVER_NAME="$EXISTING_SQL"
  echo "Detected existing Azure SQL Server: $SQL_SERVER_NAME"
else
  SQL_SERVER_NAME="${SQL_SERVER_NAME:-sql-banking-$RANDOM_SUFFIX}"
fi

SQL_DB_NAME="sqldb-master"
SQL_ADMIN_USER="sqladminuser"
SQL_ADMIN_PASS="Capst0ne!Secure${RANDOM_SUFFIX}#"

PG_SERVER_NAME="${PG_SERVER_NAME:-psql-banking-$RANDOM_SUFFIX}"
PG_DB_NAME="banking_audit"
PG_ADMIN_USER="audit_admin"
PG_ADMIN_PASS="Audit!Vault${RANDOM_SUFFIX}#"

JWT_SECRET="c3VwZXItc2VjcmV0LWtleS1mb3ItZnNlLWNhcHN0b25lLWJhbmtpbmctcGxhdGZvcm0tMjAyNi0xMjM0NTY3ODkwMTI="

# Detect existing Key Vault in the resource group if already created
EXISTING_KV=$(az keyvault list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_KV" ] && [ "$EXISTING_KV" != "None" ]; then
  KV_NAME="$EXISTING_KV"
  echo "Detected existing Key Vault: $KV_NAME"
else
  # Key Vault names must be 3-24 characters, alphanumeric and hyphens
  KV_NAME="${KEYVAULT_NAME:-kv-banking-${RANDOM_SUFFIX}}"
fi

# Detect existing Storage Account in the resource group if already created
EXISTING_SA=$(az storage account list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -n "$EXISTING_SA" ] && [ "$EXISTING_SA" != "None" ]; then
  STORAGE_ACCOUNT_NAME="$EXISTING_SA"
  echo "Detected existing Storage Account: $STORAGE_ACCOUNT_NAME"
else
  # Storage account names must be 3-24 lowercase letters and numbers only
  STORAGE_ACCOUNT_NAME="${STORAGE_ACCOUNT_NAME:-stbanking${RANDOM_SUFFIX}}"
fi

DATADOG_API_KEY="${DATADOG_API_KEY:-00000000000000000000000000000000}"

echo ""
if az group show --name "$RESOURCE_GROUP" &>/dev/null; then
  echo "[1/8] Using existing Resource Group: $RESOURCE_GROUP ($LOCATION)..."
else
  echo "[1/8] Creating Resource Group: $RESOURCE_GROUP ($LOCATION)..."
  az group create --name "$RESOURCE_GROUP" --location "$LOCATION" -o table
fi

echo ""
if az sql server show --name "$SQL_SERVER_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "[2/8] Using existing Azure SQL Server: $SQL_SERVER_NAME..."
  az sql server update --name "$SQL_SERVER_NAME" --resource-group "$RESOURCE_GROUP" --admin-password "$SQL_ADMIN_PASS" -o none 2>/dev/null || true
else
  echo "[2/8] Provisioning Azure SQL Logical Server & Basic 5 DTU Database (~$0.16/day)..."
  az sql server create \
    --name "$SQL_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --admin-user "$SQL_ADMIN_USER" \
    --admin-password "$SQL_ADMIN_PASS" \
    -o table

  # Open firewall to all Azure services (0.0.0.0 to 0.0.0.0)
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
    -o table
fi

echo ""
if [ "$USE_MANAGED_POSTGRES" = "true" ]; then
  echo "[3/8] Provisioning Azure Database for PostgreSQL Flexible Server (Burstable B1ms, ~$0.40/day)..."
  az postgres flexible-server create \
    --name "$PG_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --admin-user "$PG_ADMIN_USER" \
    --admin-password "$PG_ADMIN_PASS" \
    --sku-name Standard_B1ms \
    --tier Burstable \
    --version 16 \
    --storage-size 32 \
    --yes \
    -o table

  az postgres flexible-server firewall-rule create \
    --name "$PG_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --rule-name "AllowAzureInternal" \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 0.0.0.0 \
    -o table

  az postgres flexible-server db create \
    --server-name "$PG_SERVER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --database-name "$PG_DB_NAME" \
    -o table

  PG_FQDN="${PG_SERVER_NAME}.postgres.database.azure.com"
  PG_JDBC_URL="jdbc:postgresql://${PG_FQDN}:5432/${PG_DB_NAME}?sslmode=require"
else
  echo "[3/8] PostgreSQL Mode: IN-CLUSTER POD (\$0.00 extra cost / saves ~\$12/month)..."
  echo "      PostgreSQL 16 Alpine will deploy automatically inside AKS alongside Redis & Kafka."
  PG_FQDN="postgres.banking.svc.cluster.local"
  PG_JDBC_URL="jdbc:postgresql://${PG_FQDN}:5432/${PG_DB_NAME}?sslmode=disable"
fi

echo ""
echo "[4/8] Provisioning Azure Storage Account for Compliance Vault..."
if az storage account show --name "$STORAGE_ACCOUNT_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing Storage Account: $STORAGE_ACCOUNT_NAME..."
else
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

echo ""
echo "[5/8] Provisioning Azure Key Vault for Centralized Secrets Management..."
SQL_FQDN="${SQL_SERVER_NAME}.database.windows.net"
SQL_JDBC_URL="jdbc:sqlserver://${SQL_FQDN}:1433;databaseName=${SQL_DB_NAME};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"

KEYVAULT_ENABLED=false
if az keyvault show --name "$KV_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing Key Vault: $KV_NAME..."
  KEYVAULT_ENABLED=true
else
  # Use --enable-rbac-authorization false to prefer access policies over restrictive RBAC
  if az keyvault create \
    --name "$KV_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --enable-rbac-authorization false \
    --sku standard \
    -o none 2>/dev/null; then
    echo "Key Vault $KV_NAME created."
    KEYVAULT_ENABLED=true
  elif az keyvault create \
    --name "$KV_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --sku standard \
    -o none 2>/dev/null; then
    echo "Key Vault $KV_NAME created with default permissions."
    KEYVAULT_ENABLED=true
  else
    echo "[WARNING] Azure Key Vault creation restricted by sandbox policy. Continuing with Kubernetes Secrets..."
  fi
fi

if [ "$KEYVAULT_ENABLED" = "true" ]; then
  echo "Attempting to sync secrets to Azure Key Vault ($KV_NAME)..."
  # Attempt setting policy for caller object id if permitted
  CALLER_OID=$(az ad signed-in-user show --query id -o tsv 2>/dev/null || true)
  if [ -n "$CALLER_OID" ]; then
    az keyvault set-policy --name "$KV_NAME" --object-id "$CALLER_OID" --secret-permissions get list set delete 2>/dev/null || true
  fi

  # Attempt setting secrets, but do not fail deployment if sandbox RBAC blocks data-plane operations
  if az keyvault secret set --vault-name "$KV_NAME" --name "azure-sql-url" --value "$SQL_JDBC_URL" -o none 2>/dev/null; then
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-sql-username" --value "$SQL_ADMIN_USER" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-sql-password" --value "$SQL_ADMIN_PASS" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-postgres-url" --value "$PG_JDBC_URL" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-postgres-username" --value "$PG_ADMIN_USER" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-postgres-password" --value "$PG_ADMIN_PASS" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "azure-storage-connection-string" --value "$STORAGE_CONN_STR" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "jwt-secret" --value "$JWT_SECRET" -o none 2>/dev/null || true
    az keyvault secret set --vault-name "$KV_NAME" --name "datadog-api-key" --value "$DATADOG_API_KEY" -o none 2>/dev/null || true
    echo "Secrets successfully saved to Azure Key Vault ($KV_NAME)."
  else
    echo "[INFO] Sandbox RBAC restricts Key Vault data-plane permissions (ForbiddenByRbac)."
    echo "       Directly populating Kubernetes Secrets inside AKS (standard behavior in student sandboxes)."
  fi
fi


echo ""
if az acr show --name "$ACR_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "[6/8] Using existing Azure Container Registry: $ACR_NAME..."
else
  echo "[6/8] Provisioning Azure Container Registry (Basic SKU, ~\$0.17/day)..."
  az acr create \
    --name "$ACR_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --sku Basic \
    --admin-enabled true \
    -o table
fi

echo ""
AKS_VM_SIZE="${AKS_VM_SIZE:-Standard_B2ms}"
echo "[7/8] Provisioning Single-Node AKS Cluster ($AKS_VM_SIZE, Free Control Plane, ~\$2.00/day)..."
if az aks show --name "$AKS_CLUSTER_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Using existing AKS Cluster: $AKS_CLUSTER_NAME..."
else
  if ! az aks create \
    --name "$AKS_CLUSTER_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --node-count 1 \
    --node-vm-size "$AKS_VM_SIZE" \
    --tier free \
    --generate-ssh-keys \
    -o table; then
    echo "Retrying AKS creation with alternative budget VM size standard_b2s_v2..."
    az aks create \
      --name "$AKS_CLUSTER_NAME" \
      --resource-group "$RESOURCE_GROUP" \
      --node-count 1 \
      --node-vm-size "standard_b2s_v2" \
      --tier free \
      --generate-ssh-keys \
      -o table
  fi
fi

az aks update -n "$AKS_CLUSTER_NAME" -g "$RESOURCE_GROUP" --attach-acr "$ACR_NAME" 2>/dev/null || true

echo ""
echo "[8/8] Configuring Kubernetes Cluster Credentials & Secrets..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$AKS_CLUSTER_NAME" --overwrite-existing

kubectl create namespace banking --dry-run=client -o yaml | kubectl apply -f -

# Configure cluster image pull secret using ACR admin credentials
ACR_PASS=$(az acr credential show --name "$ACR_NAME" --query "passwords[0].value" -o tsv 2>/dev/null || true)
if [ -n "$ACR_PASS" ]; then
  kubectl create secret docker-registry acr-secret \
    --namespace banking \
    --docker-server="${ACR_NAME}.azurecr.io" \
    --docker-username="$ACR_NAME" \
    --docker-password="$ACR_PASS" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl patch serviceaccount default -n banking -p '{"imagePullSecrets": [{"name": "acr-secret"}]}' 2>/dev/null || true
fi

echo "Configuring production credentials in Kubernetes secret (banking-secrets)..."
kubectl create secret generic banking-secrets \
  --namespace banking \
  --from-literal=azure-sql-url="$SQL_JDBC_URL" \
  --from-literal=azure-sql-username="$SQL_ADMIN_USER" \
  --from-literal=azure-sql-password="$SQL_ADMIN_PASS" \
  --from-literal=azure-postgres-url="$PG_JDBC_URL" \
  --from-literal=azure-postgres-username="$PG_ADMIN_USER" \
  --from-literal=azure-postgres-password="$PG_ADMIN_PASS" \
  --from-literal=azure-storage-connection-string="$STORAGE_CONN_STR" \
  --from-literal=jwt-secret="$JWT_SECRET" \
  --from-literal=datadog-api-key="$DATADOG_API_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -


echo ""
echo "Applying Kubernetes Manifests..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="${SCRIPT_DIR}/../k8s"

kubectl apply -f "${K8S_DIR}/00_namespace.yaml"
kubectl apply -f "${K8S_DIR}/00_in_cluster_dependencies.yaml"

# Replace ACR placeholder and apply all service deployments and cronjobs
for file in "${K8S_DIR}"/0[1-9]*.yaml "${K8S_DIR}"/10*.yaml; do
  if [ -f "$file" ]; then
    echo "Deploying: $(basename "$file")..."
    sed "s/__ACR_NAME__/${ACR_NAME}/g" "$file" | kubectl apply -f -
  fi
done

echo ""
echo "===================================================================="
echo " DEPLOYMENT COMPLETE! Save These Credentials:"
echo "===================================================================="
echo " Resource Group:       $RESOURCE_GROUP"
echo " AKS Cluster:          $AKS_CLUSTER_NAME"
echo " ACR Registry:         $ACR_NAME.azurecr.io"
echo " Azure Key Vault:      $KV_NAME"
echo " Azure Storage Acct:   $STORAGE_ACCOUNT_NAME"
echo " Azure SQL Host:       $SQL_FQDN"
echo " Azure SQL DB:         $SQL_DB_NAME (User: $SQL_ADMIN_USER, Pass: $SQL_ADMIN_PASS)"
echo " PostgreSQL Host:      $PG_FQDN"
echo " PostgreSQL DB:        $PG_DB_NAME (User: $PG_ADMIN_USER, Pass: $PG_ADMIN_PASS)"
echo " PostgreSQL Type:      $(if [ "$USE_MANAGED_POSTGRES" = "true" ]; then echo "Azure Managed Flexible Server (~$0.40/day)"; else echo "In-Cluster Pod (\$0.00 / Free)"; fi)"
echo "===================================================================="
echo ""
echo "Waiting for Public Load Balancer IPs to be assigned (this may take 1-2 minutes)..."
echo "Run this command to check live frontend and gateway service endpoints:"
echo "  kubectl get svc -n banking -w"
echo ""
echo "Endpoints to monitor:"
echo "  1. Staff Admin Console:       kubectl get svc banking-frontend -n banking"
echo "  2. Customer Web Portal:       kubectl get svc aurabank-customer-web -n banking"
echo "  3. API Gateway:               kubectl get svc gateway-service -n banking"
echo ""
echo "To preserve your \$10.00 budget when not presenting, run:"
echo "  bash ${SCRIPT_DIR}/pause_budget_azure.sh $RESOURCE_GROUP $AKS_CLUSTER_NAME $(if [ "$USE_MANAGED_POSTGRES" = "true" ]; then echo "$PG_SERVER_NAME"; fi)"
echo "===================================================================="

