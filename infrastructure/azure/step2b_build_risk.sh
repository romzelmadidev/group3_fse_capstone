#!/usr/bin/env bash
# ==============================================================================
# STEP 2B: Build & Push Python Risk Service (Runtime: ~2-3 mins)
# Idempotent: Builds risk-service with Datadog agent base and XGBoost
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
echo " STEP 2B: BUILD & PUSH PYTHON RISK SERVICE ($ACR_NAME.azurecr.io)"
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
