#!/usr/bin/env bash
# ==============================================================================
# FSE CAPSTONE: Cost Conservation Script - PAUSE COMPUTE
# Reduces daily spend from ~$1.73/day to ~$0.21/day
# Session-Independent: Auto-detects sandbox Resource Group and Cluster.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/load_azure_env.sh" 2>/dev/null || true

RG_NAME="${1:-${RESOURCE_GROUP:-}}"
AKS_NAME="${2:-${AKS_CLUSTER_NAME:-aks-banking-budget}}"
PG_NAME="${3:-${PG_SERVER_NAME:-}}"

if [ -z "$RG_NAME" ]; then
  echo "Error: Could not detect Resource Group. Please pass it as an argument or run 'az login'."
  exit 1
fi

echo "===================================================================="
echo " PAUSING COMPUTE RESOURCES TO CONSERVE \$10 BUDGET"
echo " Resource Group: $RG_NAME"
echo " AKS Cluster:    $AKS_NAME"
echo "===================================================================="

echo ""
echo "[1/2] Stopping AKS Cluster Nodes (releases B2s VM billing)..."
az aks stop --name "$AKS_NAME" --resource-group "$RG_NAME" --no-wait 2>/dev/null || echo "AKS already stopped or not found."
echo "AKS stop initiated."

if [ -n "$PG_NAME" ]; then
  echo ""
  echo "[2/2] Stopping PostgreSQL Flexible Server ($PG_NAME)..."
  az postgres flexible-server stop --name "$PG_NAME" --resource-group "$RG_NAME" --no-wait 2>/dev/null || true
  echo "PostgreSQL Flexible Server stop initiated."
else
  echo "[2/2] In-cluster Postgres will halt with the AKS node VM."
fi

echo ""
echo "Compute paused. Run 'resume_budget_azure.sh' before testing again."
