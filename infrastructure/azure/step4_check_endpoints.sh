#!/usr/bin/env bash
# ==============================================================================
# STEP 4: Check Live Endpoints & Public Load Balancer IPs (Runtime: ~10s)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/azure_deployment_env.sh"
if [ -f "$ENV_FILE" ]; then
  # shellcheck source=/dev/null
  source "$ENV_FILE"
fi

echo "===================================================================="
echo " STEP 4: CHECK LIVE ENDPOINTS & PUBLIC SERVICE ADDRESSES"
echo "===================================================================="
echo ""
echo "Current pod status in 'banking' namespace:"
kubectl get pods -n banking -o wide
echo ""
echo "--------------------------------------------------------------------"
echo "Public LoadBalancer Services:"
echo "--------------------------------------------------------------------"
kubectl get svc -n banking | grep -E "LoadBalancer|NAME" || true
echo "--------------------------------------------------------------------"

BF_IP=$(kubectl get svc banking-frontend -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
AURA_IP=$(kubectl get svc aurabank-customer-web -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)
GW_IP=$(kubectl get svc gateway-service -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)

echo ""
if [ -n "$BF_IP" ]; then
  echo "1. Staff Admin Console (React):        http://$BF_IP"
else
  echo "1. Staff Admin Console (React):        (Pending Public IP... run again in 30s)"
fi

if [ -n "$AURA_IP" ]; then
  echo "2. Customer Retail Portal (Flutter):   http://$AURA_IP"
else
  echo "2. Customer Retail Portal (Flutter):   (Pending Public IP... run again in 30s)"
fi

if [ -n "$GW_IP" ]; then
  echo "3. Core API Gateway:                   http://${GW_IP}:8080"
else
  echo "3. Core API Gateway:                   (Pending Public IP... run again in 30s)"
fi

echo ""
echo "To view MailHog mock email inbox:"
echo "  kubectl port-forward svc/mailhog 8025:8025 -n banking"
echo "  Then visit: http://localhost:8025"
echo ""
echo "To pause AKS nodes and preserve your \$10 budget:"
echo "  bash infrastructure/azure/pause_budget_azure.sh"
echo "===================================================================="
