#!/usr/bin/env bash
# ==============================================================================
# FSE CAPSTONE: Automated Master Deployment Runner for Microsoft Azure
# Executes end-to-end cloud provisioning, image builds, secret sync, and K8s rollout.
# Budget Constraint: Total Azure Spend <= $10.00 USD.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "===================================================================="
echo "    AuraBank Core Banking Platform - Automated Azure Master Runner   "
echo "===================================================================="
echo "Current Time: $(date)"
echo "Working Directory: $ROOT_DIR"
echo ""

# 1. Pre-flight Check: Azure CLI Login
echo "[Phase 1/4] Verifying Azure CLI Authentication & Active Subscription..."
if ! command -v az &> /dev/null; then
  echo "Error: Azure CLI ('az') is not installed or not in PATH."
  echo "Please install Azure CLI or execute inside Azure Cloud Shell: https://shell.azure.com"
  exit 1
fi

ACCOUNT_INFO=$(az account show -o json 2>/dev/null || true)
if [ -z "$ACCOUNT_INFO" ]; then
  echo "Error: You are not logged into Azure CLI."
  echo "Please run 'az login' before launching this script."
  exit 1
fi

SUB_NAME=$(echo "$ACCOUNT_INFO" | jq -r '.name // empty')
SUB_ID=$(echo "$ACCOUNT_INFO" | jq -r '.id // empty')
echo "Active Subscription: $SUB_NAME ($SUB_ID)"

# 2. Infrastructure Provisioning & Secret Synchronization
echo ""
echo "[Phase 2/4] Provisioning Azure Cloud Infrastructure & Secrets..."
bash "${SCRIPT_DIR}/deploy_budget_azure.sh"

# Determine ACR name and Resource Group
RESOURCE_GROUP="${RESOURCE_GROUP:-}"
if [ -z "$RESOURCE_GROUP" ]; then
  RESOURCE_GROUP=$(az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name, 'cloud-shell-storage')].name | [0]" -o tsv 2>/dev/null || true)
fi

ACR_NAME=$(az acr list --resource-group "$RESOURCE_GROUP" --query "[0].name" -o tsv 2>/dev/null || true)
if [ -z "$ACR_NAME" ] || [ "$ACR_NAME" = "None" ]; then
  echo "Error: Could not automatically detect Azure Container Registry in resource group $RESOURCE_GROUP."
  exit 1
fi

echo "Target Azure Container Registry: ${ACR_NAME}.azurecr.io"

# 3. Multi-Stage Container Builds & Push
echo ""
echo "[Phase 3/4] Building and Pushing Container Images to ACR..."
bash "${SCRIPT_DIR}/build_and_push_images.sh" "$ACR_NAME"

# 4. Trigger Rolling Restart & Verification
echo ""
echo "[Phase 4/4] Triggering Kubernetes Rolling Updates & Verifying Health..."
kubectl rollout restart deployment -n banking

echo "Waiting for services to become ready..."
DEPLOYMENTS=(
  "redis"
  "kafka"
  "postgres"
  "gateway-service"
  "account-service"
  "transfer-orchestrator"
  "t24-mock-cbs"
  "compliance-service"
  "notification-service"
  "risk-service"
  "banking-frontend"
  "aurabank-customer-web"
)

for dep in "${DEPLOYMENTS[@]}"; do
  echo "Checking rollout status for deployment: $dep..."
  kubectl rollout status deployment/"$dep" -n banking --timeout=180s || true
done

echo ""
echo "===================================================================="
echo "                   DEPLOYMENT COMPLETE!                             "
echo "===================================================================="
echo "Active Public Load Balancer Endpoints:"
echo "--------------------------------------------------------------------"
kubectl get svc -n banking -o wide | grep LoadBalancer || true
echo "===================================================================="
echo ""
echo "Accessing the Banking Applications:"
echo "  1. Staff Admin Console (React):"
echo "     http://\$(kubectl get svc banking-frontend -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
echo ""
echo "  2. Customer Retail Portal (Flutter Web):"
echo "     http://\$(kubectl get svc aurabank-customer-web -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}')"
echo ""
echo "  3. Core API Gateway (Spring Cloud Gateway):"
echo "     http://\$(kubectl get svc gateway-service -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}'):8080"
echo ""
echo "  4. Mock MailHog Web Inbox (Local Port-Forward):"
echo "     kubectl port-forward svc/mailhog 8025:8025 -n banking"
echo "     Then open http://localhost:8025 in your browser"
echo ""
echo "To preserve your \$10.00 Azure budget, remember to pause the cluster:"
echo "  bash ${SCRIPT_DIR}/pause_budget_azure.sh"
echo "===================================================================="
