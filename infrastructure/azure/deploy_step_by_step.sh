#!/usr/bin/env bash
# ==============================================================================
# AuraBank Step-by-Step Interactive Deployment Runner for Azure Cloud Shell
# Fully Session-Independent: Run across any session, tab, or reconnect.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while true; do
  # Load active environment across any session
  # shellcheck source=/dev/null
  source "${SCRIPT_DIR}/load_azure_env.sh" 2>/dev/null || true

  echo ""
  echo "===================================================================="
  echo "       AuraBank Modular Cloud Deployment (Session-Independent)      "
  echo "===================================================================="
  echo " Context:  RG:  ${RESOURCE_GROUP:-Auto-detecting...}"
  echo "           ACR: ${ACR_NAME:-(Not provisioned yet)}"
  echo "           AKS: ${AKS_CLUSTER_NAME:-(Not provisioned yet)}"
  echo "--------------------------------------------------------------------"
  echo "  1) Step 1: Provision Infrastructure (SQL, Storage, ACR, AKS) [~3-5m]"
  echo "  2) Step 2A: Build & Push Java Microservices (6 services)      [~4-6m]"
  echo "  3) Step 2B: Build & Push Python Risk Service                  [~2-3m]"
  echo "  4) Step 2C: Build & Push Frontends (React & Flutter Web)      [~3-5m]"
  echo "  5) Step 3: Configure K8s Secrets & Deploy Workloads           [~2m]"
  echo "  6) Step 4: Check Live Endpoints & Public IPs                  [~10s]"
  echo "--------------------------------------------------------------------"
  echo "  P) Pause AKS Compute (Preserve \$10 Budget)"
  echo "  R) Resume AKS Compute"
  echo "  Q) Quit"
  echo "===================================================================="
  read -r -p "Select a step to run [1-6, P, R, Q]: " choice

  case "$choice" in
    1)
      bash "${SCRIPT_DIR}/step1_infra_provision.sh"
      ;;
    2)
      read -r -p "Build all 6 services or a specific one? [Enter for all, or service name e.g. gateway-service]: " svc_choice
      if [ -n "$svc_choice" ]; then
        bash "${SCRIPT_DIR}/step2a_build_backend.sh" "$svc_choice"
      else
        bash "${SCRIPT_DIR}/step2a_build_backend.sh"
      fi
      ;;
    3)
      bash "${SCRIPT_DIR}/step2b_build_risk.sh"
      ;;
    4)
      read -r -p "Build both frontends or a specific one? [Enter for both, or banking-frontend / aurabank-web]: " fe_choice
      if [ -n "$fe_choice" ]; then
        bash "${SCRIPT_DIR}/step2c_build_frontends.sh" "$fe_choice"
      else
        bash "${SCRIPT_DIR}/step2c_build_frontends.sh"
      fi
      ;;
    5)
      bash "${SCRIPT_DIR}/step3_k8s_deploy.sh"
      ;;
    6)
      bash "${SCRIPT_DIR}/step4_check_endpoints.sh"
      ;;
    [pP])
      bash "${SCRIPT_DIR}/pause_budget_azure.sh"
      ;;
    [rR])
      bash "${SCRIPT_DIR}/resume_budget_azure.sh"
      ;;
    [qQ])
      echo "Exiting deployment menu. Have a great day!"
      exit 0
      ;;
    *)
      echo "Invalid option. Please choose 1, 2, 3, 4, 5, 6, P, R, or Q."
      ;;
  esac
done
