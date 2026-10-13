#!/usr/bin/env bash
# ==============================================================================
# STEP 2C: Build & Push Frontend Applications (Runtime: ~3-5 mins)
# Session-Independent: Resolves ACR across any session / tab automatically.
# Usage:
#   bash step2c_build_frontends.sh                  # Builds both frontends
#   bash step2c_build_frontends.sh banking-frontend # Builds Staff React Console
#   bash step2c_build_frontends.sh aurabank-web     # Builds Customer Flutter Web
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Load environment dynamically across any session
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/load_azure_env.sh"

if [ -z "${ACR_NAME:-}" ]; then
  echo "Error: Azure Container Registry (ACR) could not be detected in Resource Group '$RESOURCE_GROUP'."
  echo "Please run 'bash infrastructure/azure/step1_infra_provision.sh' first or export ACR_NAME=<your_acr>."
  exit 1
fi

echo "===================================================================="
echo " STEP 2C: BUILD & PUSH FRONTENDS TO ACR ($ACR_NAME.azurecr.io)"
echo " Resource Group: $RESOURCE_GROUP"
echo "===================================================================="

ACR_LOGIN_SERVER="${ACR_NAME}.azurecr.io"

USE_AZ_ACR_BUILD=false
if ! command -v docker &> /dev/null || ! docker info &> /dev/null; then
  echo "Using serverless 'az acr build' in cloud..."
  USE_AZ_ACR_BUILD=true
else
  echo "Local Docker detected. Logging into $ACR_LOGIN_SERVER..."
  az acr login --name "$ACR_NAME"
fi

TARGET_FRONTEND="${1:-all}"

# 1. Staff Admin Console (React)
if [ "$TARGET_FRONTEND" = "all" ] || [ "$TARGET_FRONTEND" = "banking-frontend" ] || [ "$TARGET_FRONTEND" = "frontend" ]; then
  echo "------------------------------------------------------------"
  echo "Building Staff Admin Console: banking-frontend..."
  echo "------------------------------------------------------------"
  if [ "$USE_AZ_ACR_BUILD" = "true" ]; then
    az acr build \
      --registry "$ACR_NAME" \
      --image "banking-frontend:latest" \
      --file "${ROOT_DIR}/frontend/Dockerfile" \
      "${ROOT_DIR}/frontend"
  else
    FRONTEND_TAG="${ACR_LOGIN_SERVER}/banking-frontend:latest"
    docker build -f "${ROOT_DIR}/frontend/Dockerfile" -t "$FRONTEND_TAG" "${ROOT_DIR}/frontend"
    docker push "$FRONTEND_TAG"
  fi
  echo "Finished banking-frontend."
fi

# 2. Customer Retail Portal (Flutter Web, build context: ROOT_DIR)
if [ "$TARGET_FRONTEND" = "all" ] || [ "$TARGET_FRONTEND" = "aurabank-web" ] || [ "$TARGET_FRONTEND" = "customer" ]; then
  echo "------------------------------------------------------------"
  echo "Building Customer Retail Portal: aurabank-web..."
  echo "------------------------------------------------------------"
  if [ "$USE_AZ_ACR_BUILD" = "true" ]; then
    az acr build \
      --registry "$ACR_NAME" \
      --image "aurabank-web:latest" \
      --file "${ROOT_DIR}/aurabank_web/Dockerfile" \
      "${ROOT_DIR}"
  else
    AURA_TAG="${ACR_LOGIN_SERVER}/aurabank-web:latest"
    docker build -f "${ROOT_DIR}/aurabank_web/Dockerfile" -t "$AURA_TAG" "${ROOT_DIR}"
    docker push "$AURA_TAG"
  fi
  echo "Finished aurabank-web."
fi

echo ""
echo "===================================================================="
echo " STEP 2C COMPLETE! Frontend(s) pushed to $ACR_NAME."
echo " Next step: Run 'bash infrastructure/azure/step3_k8s_deploy.sh'"
echo "===================================================================="
