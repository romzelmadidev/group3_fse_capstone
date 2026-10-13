#!/usr/bin/env bash
# ==============================================================================
# STEP 2A: Build & Push Java Microservices (Runtime: ~4-6 mins)
# Session-Independent: Resolves ACR across any session / tab automatically.
# Usage:
#   bash step2a_build_backend.sh                 # Builds all 6 Java microservices
#   bash step2a_build_backend.sh gateway-service # Builds only gateway-service
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
echo " STEP 2A: BUILD & PUSH JAVA MICROSERVICES TO ACR ($ACR_NAME.azurecr.io)"
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

TARGET_SERVICE="${1:-all}"
if [ "$TARGET_SERVICE" != "all" ]; then
  JAVA_SERVICES=("$TARGET_SERVICE")
else
  JAVA_SERVICES=(
    "gateway-service"
    "account-service"
    "transfer-orchestrator"
    "t24-mock-cbs"
    "compliance-service"
    "notification-service"
  )
fi

for svc in "${JAVA_SERVICES[@]}"; do
  echo "------------------------------------------------------------"
  echo "Building: $svc..."
  echo "------------------------------------------------------------"
  DOCKERFILE_PATH="${ROOT_DIR}/backend/${svc}/Dockerfile"
  if [ "$USE_AZ_ACR_BUILD" = "true" ]; then
    az acr build \
      --registry "$ACR_NAME" \
      --image "${svc}:latest" \
      --file "$DOCKERFILE_PATH" \
      "${ROOT_DIR}/backend"
  else
    IMAGE_TAG="${ACR_LOGIN_SERVER}/${svc}:latest"
    docker build -f "$DOCKERFILE_PATH" -t "$IMAGE_TAG" "${ROOT_DIR}/backend"
    docker push "$IMAGE_TAG"
  fi
  echo "Finished $svc."
done

echo ""
echo "===================================================================="
echo " STEP 2A COMPLETE! Microservice(s) pushed to $ACR_NAME."
echo " Next step: Run 'bash infrastructure/azure/step2b_build_risk.sh'"
echo "===================================================================="
