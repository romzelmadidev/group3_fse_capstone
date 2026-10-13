#!/usr/bin/env bash
# ==============================================================================
# STEP 2A: Build & Push Java Microservices (Runtime: ~4-6 mins)
# Idempotent: Builds gateway, account, transfer-orchestrator, t24, compliance, notification
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
echo " STEP 2A: BUILD & PUSH JAVA MICROSERVICES TO ACR ($ACR_NAME.azurecr.io)"
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

JAVA_SERVICES=(
  "gateway-service"
  "account-service"
  "transfer-orchestrator"
  "t24-mock-cbs"
  "compliance-service"
  "notification-service"
)

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
echo " STEP 2A COMPLETE! All 6 Java microservices pushed to $ACR_NAME."
echo " Next step: Run 'bash infrastructure/azure/step2b_build_risk.sh'"
echo "===================================================================="
