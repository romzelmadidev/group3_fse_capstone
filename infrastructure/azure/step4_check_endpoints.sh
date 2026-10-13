#!/usr/bin/env bash
# ==============================================================================
# STEP 4: Check Live Endpoints & Public Load Balancer IPs (Runtime: ~10s)
# Session-Independent: Auto-reconnects kubectl in any session or terminal tab.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load environment dynamically across any session
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/load_azure_env.sh"

echo "===================================================================="
echo " STEP 4: CHECK LIVE ENDPOINTS & PUBLIC SERVICE ADDRESSES"
echo " Resource Group: ${RESOURCE_GROUP:-unknown} | Cluster: ${AKS_CLUSTER_NAME:-unknown}"
echo "===================================================================="

# Ensure kubectl is connected to the cluster in this session
if ! kubectl get namespace banking &>/dev/null; then
  if [ -n "${AKS_CLUSTER_NAME:-}" ] && [ -n "${RESOURCE_GROUP:-}" ]; then
    echo "Connecting kubectl to AKS ($AKS_CLUSTER_NAME)..."
    az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$AKS_CLUSTER_NAME" --overwrite-existing 2>/dev/null || true
  fi
fi

echo ""
echo "Current pod status in 'banking' namespace:"
kubectl get pods -n banking -o wide 2>/dev/null || echo "Pods not available yet."
echo ""
echo "--------------------------------------------------------------------"
echo "Public LoadBalancer Services:"
echo "--------------------------------------------------------------------"
kubectl get svc -n banking 2>/dev/null | grep -E "LoadBalancer|NAME" || true
echo "--------------------------------------------------------------------"

BF_IP=$(kubectl get svc banking-frontend -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
AURA_IP=$(kubectl get svc aurabank-customer-web -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
GW_IP=$(kubectl get svc gateway-service -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)

echo ""
if [ -n "$BF_IP" ]; then
  echo "1. Staff Admin Console (React):        http://$BF_IP"
else
  echo "1. Staff Admin Console (React):        (Pending Public IP... wait ~30s and rerun)"
fi

if [ -n "$AURA_IP" ]; then
  echo "2. Customer Retail Portal (Flutter):   http://$AURA_IP"
else
  echo "2. Customer Retail Portal (Flutter):   (Pending Public IP... wait ~30s and rerun)"
fi

if [ -n "$GW_IP" ]; then
  echo "3. Core API Gateway:                   http://${GW_IP}:8080"
else
  echo "3. Core API Gateway:                   (Pending Public IP... wait ~30s and rerun)"
fi

echo ""
echo "To view MailHog mock email inbox:"
echo "  kubectl port-forward svc/mailhog 8025:8025 -n banking"
echo "  Then visit: http://localhost:8025"
echo ""
echo "To pause AKS nodes and preserve your \$10 budget:"
echo "  bash infrastructure/azure/pause_budget_azure.sh"
echo "===================================================================="
