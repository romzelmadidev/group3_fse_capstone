#!/usr/bin/env bash
# ==============================================================================
# FSE CAPSTONE: Cost Conservation Script - RESUME COMPUTE
# Restores AKS node VM compute before testing or presentations (~2 mins)
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
echo " RESUMING COMPUTE RESOURCES FOR TESTING / PRESENTATIONS"
echo " Resource Group: $RG_NAME"
echo " AKS Cluster:    $AKS_NAME"
echo "===================================================================="

echo ""
echo "[1/2] Starting AKS Cluster Nodes (re-allocating VM compute)..."
az aks start --name "$AKS_NAME" --resource-group "$RG_NAME" --no-wait 2>/dev/null || echo "AKS already running or not found."
echo "AKS start initiated."

if [ -n "$PG_NAME" ]; then
  echo ""
  echo "[2/2] Starting PostgreSQL Flexible Server ($PG_NAME)..."
  az postgres flexible-server start --name "$PG_NAME" --resource-group "$RG_NAME" --no-wait 2>/dev/null || true
  echo "PostgreSQL Flexible Server start initiated."
fi

echo ""
echo "Compute resumed. Pods will become ready in ~1-2 minutes."
echo "Check status with: bash infrastructure/azure/step4_check_endpoints.sh"
