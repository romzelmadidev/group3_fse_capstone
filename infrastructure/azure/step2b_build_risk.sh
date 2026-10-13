#!/usr/bin/env bash
# ==============================================================================
# STEP 2B: Build & Push Python Risk Service (Runtime: ~2-3 mins)
# Session-Independent: Resolves ACR across any session / tab automatically.
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
echo " STEP 2B: BUILD & PUSH PYTHON RISK SERVICE ($ACR_NAME.azurecr.io)"
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

echo "Building and pushing risk-service..."
if [ "$USE_AZ_ACR_BUILD" = "true" ]; then
  az acr build \
    --registry "$ACR_NAME" \
    --image "risk-service:latest" \
    --file "${ROOT_DIR}/backend/risk-service/Dockerfile" \
    "${ROOT_DIR}/backend/risk-service"
else
  RISK_TAG="${ACR_LOGIN_SERVER}/risk-service:latest"
  docker build -f "${ROOT_DIR}/backend/risk-service/Dockerfile" -t "$RISK_TAG" "${ROOT_DIR}/backend/risk-service"
  docker push "$RISK_TAG"
fi

echo ""
echo "===================================================================="
echo " STEP 2B COMPLETE! risk-service pushed to $ACR_NAME."
echo " Next step: Run 'bash infrastructure/azure/step2c_build_frontends.sh'"
echo "===================================================================="
