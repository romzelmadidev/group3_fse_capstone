#!/usr/bin/env bash
# ==============================================================================
# STEP 3: Configure Kubernetes Secrets & Deploy Microservices (Runtime: ~2 mins)
# Idempotent: Connects to AKS, syncs secrets, applies k8s manifests, restarts pods
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

ENV_FILE="${SCRIPT_DIR}/azure_deployment_env.sh"
if [ ! -f "$ENV_FILE" ]; then
  echo "Error: $ENV_FILE not found. Please run 'bash infrastructure/azure/step1_infra_provision.sh' first."
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

echo "===================================================================="
echo " STEP 3: CONFIGURE KUBERNETES & DEPLOY WORKLOADS ($AKS_CLUSTER_NAME)"
echo "===================================================================="

# 1. Get Cluster Credentials
echo "[1/4] Connecting kubectl to AKS cluster ($AKS_CLUSTER_NAME in $RESOURCE_GROUP)..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$AKS_CLUSTER_NAME" --overwrite-existing

# 2. Namespace & ACR Secret
echo "[2/4] Ensuring banking namespace and image pull credentials..."
kubectl create namespace banking --dry-run=client -o yaml | kubectl apply -f -

ACR_LOGIN_SERVER="${ACR_NAME}.azurecr.io"
ACR_PASS=$(az acr credential show --name "$ACR_NAME" --query "passwords[0].value" -o tsv 2>/dev/null || true)
if [ -n "$ACR_PASS" ]; then
  kubectl create secret docker-registry acr-secret \
    --namespace banking \
    --docker-server="$ACR_LOGIN_SERVER" \
    --docker-username="$ACR_NAME" \
    --docker-password="$ACR_PASS" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl patch serviceaccount default -n banking -p '{"imagePullSecrets": [{"name": "acr-secret"}]}' 2>/dev/null || true
fi

# 3. Synchronize Secrets directly into banking-secrets
echo "[3/4] Injecting credentials into Kubernetes secret 'banking-secrets'..."
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

# 4. Apply Kubernetes Manifests
echo "[4/4] Applying manifests with ACR replacement ($ACR_NAME)..."
K8S_DIR="${ROOT_DIR}/infrastructure/k8s"

kubectl apply -f "${K8S_DIR}/00_namespace.yaml"
kubectl apply -f "${K8S_DIR}/00_in_cluster_dependencies.yaml"

for file in "${K8S_DIR}"/0[1-9]*.yaml "${K8S_DIR}"/10*.yaml; do
  if [ -f "$file" ]; then
    echo "Applying $(basename "$file")..."
    sed "s/__ACR_NAME__/${ACR_NAME}/g" "$file" | kubectl apply -f -
  fi
done

echo "Triggering rolling update for latest images..."
kubectl rollout restart deployment -n banking 2>/dev/null || true

echo ""
echo "===================================================================="
echo " STEP 3 COMPLETE! All workloads deployed to AKS."
echo " Next step: Run 'bash infrastructure/azure/step4_check_endpoints.sh'"
echo "===================================================================="
