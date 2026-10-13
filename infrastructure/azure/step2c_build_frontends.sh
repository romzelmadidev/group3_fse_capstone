#!/usr/bin/env bash
# ==============================================================================
# STEP 2C: Build & Push Frontend Applications (Runtime: ~3-5 mins)
# Idempotent: Builds Staff React Console (banking-frontend) and Flutter Customer Portal (aurabank-web)
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
echo " STEP 2C: BUILD & PUSH FRONTENDS TO ACR ($ACR_NAME.azurecr.io)"
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

# 1. Staff Admin Console (React)
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

# 2. Customer Retail Portal (Flutter Web, build context: ROOT_DIR)
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

echo ""
echo "===================================================================="
echo " STEP 2C COMPLETE! Frontends pushed to $ACR_NAME."
echo " Next step: Run 'bash infrastructure/azure/step3_k8s_deploy.sh'"
echo "===================================================================="
