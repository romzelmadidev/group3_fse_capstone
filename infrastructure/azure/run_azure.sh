#!/usr/bin/env bash
# ==============================================================================
# AuraBank: ONE-COMMAND Azure deployment (safe to re-run, works in any account)
#
#   Run in Azure Cloud Shell (Bash) from the repo root:
#       bash infrastructure/azure/run_azure.sh            # deploy / repair everything
#       bash infrastructure/azure/run_azure.sh status     # show pods + URLs only
#
# What it does (each step is idempotent):
#   1. Pre-flight  : tools, login, resource providers
#   2. Infra       : Resource Group, Azure SQL, Storage, ACR, AKS (sized so pods fit)
#   3. Cluster     : starts a paused AKS, scales nodes, secrets, ACR pull access
#   4. Database    : t24-mock-cbs (Flyway) builds the Azure SQL tables on first start
#   5. Images      : builds only the images missing from ACR (in parallel)
#   6. Deploy      : in-cluster deps -> all services -> waits until Ready
#   7. Self-heal   : adds a node if any pod is Pending ("Insufficient memory/cpu")
#   8. Smoke test  : gateway, login path (gateway->account-service->SQL), web apps
#
# Optional environment variables:
#   RESOURCE_GROUP   use this RG (default: auto-detect, else create rg-aurabank-<id>)
#   LOCATION         region for a NEW RG (default: southeastasia)
#   SQL_SERVER       custom Azure SQL server name (default: auto-detect, else sql-aurabank-<suffix>)
#   SQL_LOCATION     region for Azure SQL if your subscription blocks LOCATION
#   NODE_COUNT       AKS nodes to start with            (default: 2)
#   MAX_NODES        upper bound for self-healing scale (default: 4)
#   AKS_VM_SIZE      force a VM size, e.g. Standard_D2s_v3 (default: try several)
#   REBUILD          "all" or comma list, e.g. "gateway-service,account-service"
#   RESET_DB         "true" deletes + recreates the Azure SQL database (DELETES ALL DATA);
#                    use it once if a previous attempt left half-created tables
#   DATADOG_API_KEY  real key to enable Datadog (default: dummy 00000000000000000000000000000000)
#   DATADOG_SITE     Datadog intake site (default: datadoghq.com)
#   INTERBANK_API_KEY shared verification key for interbank transfers (default: AURABANK-SECURE-KEY-2026)
#   PARTNER_BANK_URL target endpoint URL for outward interbank transfers
#                    (default: http://transfer-orchestrator.banking.svc.cluster.local:8082/api/v1/mock-partner/transfers)
# ==============================================================================
set -Eeuo pipefail
trap 'echo "[ERROR] line $LINENO: command failed: $BASH_COMMAND" >&2' ERR

MODE="${1:-deploy}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
K8S_DIR="${ROOT}/infrastructure/k8s"
LOG_DIR="${TMPDIR:-/tmp}/aurabank-logs"
mkdir -p "$LOG_DIR"

NODE_COUNT="${NODE_COUNT:-2}"
MAX_NODES="${MAX_NODES:-4}"
REBUILD="${REBUILD:-}"
RESET_DB="${RESET_DB:-false}"
BUILD_PARALLEL="${BUILD_PARALLEL:-3}"
READY_TIMEOUT="${READY_TIMEOUT:-1500}"
DATADOG_API_KEY="${DATADOG_API_KEY:-}"
DATADOG_SITE="${DATADOG_SITE:-datadoghq.com}"
INTERBANK_API_KEY="${INTERBANK_API_KEY:-${PARTNER_BANK_API_KEY:-}}"
PARTNER_BANK_URL="${PARTNER_BANK_URL:-}"
JWT_SECRET="${JWT_SECRET:-c3VwZXItc2VjcmV0LWtleS1mb3ItZnNlLWNhcHN0b25lLWJhbmtpbmctcGxhdGZvcm0tMjAyNi0xMjM0NTY3ODkwMTI=}"

SQL_DB_NAME="sqldb-master"
SQL_ADMIN_USER="sqladminuser"
PG_DB_NAME="banking_audit"
PG_ADMIN_USER="audit_admin"
PG_JDBC_URL="jdbc:postgresql://postgres.banking.svc.cluster.local:5432/${PG_DB_NAME}?sslmode=disable"

# image | Dockerfile (from repo root) | build context (from repo root) | k8s deployment
IMAGES=(
  "gateway-service|backend/gateway-service/Dockerfile|backend|gateway-service"
  "account-service|backend/account-service/Dockerfile|backend|account-service"
  "transfer-orchestrator|backend/transfer-orchestrator/Dockerfile|backend|transfer-orchestrator"
  "t24-mock-cbs|backend/t24-mock-cbs/Dockerfile|backend|t24-mock-cbs"
  "compliance-service|backend/compliance-service/Dockerfile|backend|compliance-service"
  "notification-service|backend/notification-service/Dockerfile|backend|notification-service"
  "risk-service|backend/risk-service/Dockerfile|backend/risk-service|risk-service"
  "banking-frontend|frontend/Dockerfile|frontend|banking-frontend"
  "aurabank-web|aurabank_web/Dockerfile|.|aurabank-customer-web"
)

log()  { echo -e "\n\033[1;36m==> $*\033[0m"; }
info() { echo "    $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*" >&2; }
die()  { echo -e "\033[1;31m[FATAL]\033[0m $*" >&2; exit 1; }

# ------------------------------------------------------------------------------
# 1. Pre-flight
# ------------------------------------------------------------------------------
preflight() {
  log "[1/8] Pre-flight checks"
  for t in az kubectl jq curl sed; do
    command -v "$t" >/dev/null 2>&1 || die "'$t' is required but not installed (Azure Cloud Shell has all of them)."
  done
  az account show >/dev/null 2>&1 || die "Not logged in. Run 'az login' (not needed in Cloud Shell)."
  SUB_ID="$(az account show --query id -o tsv)"
  info "Subscription: $(az account show --query name -o tsv) (${SUB_ID})"
  for ns in Microsoft.ContainerService Microsoft.ContainerRegistry Microsoft.Sql \
            Microsoft.Storage Microsoft.Network Microsoft.Compute; do
    az provider register --namespace "$ns" --only-show-errors >/dev/null 2>&1 || true
  done
}

# ------------------------------------------------------------------------------
# 2. Infrastructure
# ------------------------------------------------------------------------------
first_name() { # first_name <az list command...>
  az "$@" --resource-group "$RG" --query "[0].name" -o tsv 2>/dev/null | grep -v '^None$' || true
}

gen_pass() {
  local r
  if command -v openssl >/dev/null 2>&1; then r="$(openssl rand -hex 10)"
  else r="$(date +%s%N | sha256sum | cut -c1-20)"; fi
  printf 'Aa1-%s' "$r"
}

pick_resource_group() {
  RG="${RESOURCE_GROUP:-}"
  if [ -z "$RG" ]; then
    RG="$(az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name,'cloud-shell-storage') && !starts_with(name,'MC_') && !starts_with(name,'DefaultResourceGroup')].name" -o tsv 2>/dev/null \
          | { grep -iE 'aurabank|banking' || true; } | head -n1 || true)"
  fi
  if [ -z "$RG" ]; then
    RG="$(az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name,'cloud-shell-storage') && !starts_with(name,'MC_') && !starts_with(name,'DefaultResourceGroup')].name | [0]" -o tsv 2>/dev/null | grep -v '^None$' || true)"
  fi
  if [ -z "$RG" ]; then
    RG="rg-aurabank-${SUFFIX}"
    info "Creating Resource Group $RG (${LOCATION:-southeastasia})"
    az group create --name "$RG" --location "${LOCATION:-southeastasia}" -o none
  fi
  LOC="$(az group show --name "$RG" --query location -o tsv)"
  info "Resource Group: $RG ($LOC)   [override with RESOURCE_GROUP=...]"
  # SQL/ACR/Storage names are GLOBALLY unique in Azure. Teammates often share one subscription
  # but each has their own Resource Group, so derive the suffix from subscription + RG.
  SUFFIX="${SUFFIX:-$(printf '%s' "${SUB_ID}/${RG}" | md5sum | cut -c1-6)}"
}

load_existing_secret() { # reuse DB password already stored in the cluster, if reachable
  local aks="$1" v=""
  if az aks get-credentials -g "$RG" -n "$aks" --overwrite-existing >/dev/null 2>&1; then
    v="$(kubectl get secret banking-secrets -n banking -o jsonpath='{.data.azure-sql-password}' --request-timeout=15s 2>/dev/null | base64 -d 2>/dev/null || true)"
  fi
  printf '%s' "$v"
}

ensure_sql() {
  SQL_SERVER="${SQL_SERVER:-${SQL_SERVER_NAME:-$(first_name sql server list)}}"
  SQL_SERVER="${SQL_SERVER:-sql-aurabank-${SUFFIX}}"
  SQL_FQDN="${SQL_SERVER}.database.windows.net"
  if az sql server show -n "$SQL_SERVER" -g "$RG" >/dev/null 2>&1; then
    info "Azure SQL server exists: $SQL_SERVER"
    SQL_ADMIN_USER="$(az sql server show -n "$SQL_SERVER" -g "$RG" --query administratorLogin -o tsv)"
    local update_attempt=0 update_err="${LOG_DIR}/sql-update.err"
    while :; do
      if az sql server update -n "$SQL_SERVER" -g "$RG" --admin-password "$SQL_ADMIN_PASS" -o none 2>"$update_err"; then
        break
      fi
      if grep -qi "UpsertLogicalServerRequestAlreadyInProgress" "$update_err" && [ "$update_attempt" -lt 6 ]; then
        update_attempt=$((update_attempt + 1))
        info "An operation is currently in progress on $SQL_SERVER. Waiting for Azure to complete it (attempt ${update_attempt}/6, 20s)..."
        sleep 20
      else
        cat "$update_err" >&2
        die "Failed to update Azure SQL server $SQL_SERVER."
      fi
    done
  else
    local attempt=0 err="${LOG_DIR}/sql-create.err"
    while :; do
      info "Creating Azure SQL server $SQL_SERVER (${SQL_LOCATION:-$LOC})"
      if az sql server create -n "$SQL_SERVER" -g "$RG" -l "${SQL_LOCATION:-$LOC}" \
           --admin-user "$SQL_ADMIN_USER" --admin-password "$SQL_ADMIN_PASS" -o none 2>"$err"; then
        break
      fi
      attempt=$((attempt + 1))
      if grep -q "NameAlreadyExists" "$err" && [ "$attempt" -lt 4 ]; then
        SQL_SERVER="sql-aurabank-${SUFFIX}-$((RANDOM % 900 + 100))"
        warn "SQL server name is taken (names are global in Azure). Retrying as $SQL_SERVER"
      elif grep -qi "UpsertLogicalServerRequestAlreadyInProgress" "$err" && [ "$attempt" -lt 6 ]; then
        info "An operation is already in progress on $SQL_SERVER. Waiting 20s for Azure to finish..."
        sleep 20
      else
        cat "$err" >&2; die "Could not create the Azure SQL server. If the region is blocked, set SQL_LOCATION=<region>."
      fi
    done
    SQL_FQDN="${SQL_SERVER}.database.windows.net"
  fi
  az sql server firewall-rule create --server "$SQL_SERVER" -g "$RG" -n AllowAzureInternal \
    --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0 -o none 2>/dev/null || true
  if [ "$RESET_DB" = "true" ] && az sql db show --server "$SQL_SERVER" -g "$RG" -n "$SQL_DB_NAME" >/dev/null 2>&1; then
    warn "RESET_DB=true: deleting database $SQL_DB_NAME so t24-mock-cbs (Flyway) can rebuild it from scratch"
    az sql db delete --server "$SQL_SERVER" -g "$RG" -n "$SQL_DB_NAME" --yes -o none
  fi
  if ! az sql db show --server "$SQL_SERVER" -g "$RG" -n "$SQL_DB_NAME" >/dev/null 2>&1; then
    info "Creating database $SQL_DB_NAME (Basic, 5 DTU)"
    az sql db create --server "$SQL_SERVER" -g "$RG" -n "$SQL_DB_NAME" \
      --edition Basic --service-objective Basic --capacity 5 --backup-storage-redundancy Local --yes -o none
  fi
  SQL_JDBC_URL="jdbc:sqlserver://${SQL_FQDN}:1433;databaseName=${SQL_DB_NAME};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"
}

ensure_storage() {
  STORAGE="$(first_name storage account list)"; STORAGE="${STORAGE:-staurabank${SUFFIX}}"
  if ! az storage account show -n "$STORAGE" -g "$RG" >/dev/null 2>&1; then
    info "Creating storage account $STORAGE"
    az storage account create -n "$STORAGE" -g "$RG" -l "$LOC" --sku Standard_LRS --kind StorageV2 -o none
  else
    info "Storage account exists: $STORAGE"
  fi
  STORAGE_CONN="$(az storage account show-connection-string -n "$STORAGE" -g "$RG" --query connectionString -o tsv)"
  az storage container create -n compliance-vault --connection-string "$STORAGE_CONN" -o none 2>/dev/null || true
}

ensure_acr() {
  ACR="$(first_name acr list)"; ACR="${ACR:-acraurabank${SUFFIX}}"
  if ! az acr show -n "$ACR" -g "$RG" >/dev/null 2>&1; then
    info "Creating container registry $ACR"
    az acr create -n "$ACR" -g "$RG" --sku Basic --admin-enabled true -o none
  else
    info "Container registry exists: $ACR"
    az acr update -n "$ACR" --admin-enabled true -o none 2>/dev/null || true
  fi
}

# ------------------------------------------------------------------------------
# 3. AKS: sized so every pod can be scheduled (no "Pending")
# ------------------------------------------------------------------------------
create_aks() {
  local combos=() c size count
  if [ -n "${AKS_VM_SIZE:-}" ]; then
    combos=("${AKS_VM_SIZE}:${NODE_COUNT}")
  else
    # ~2.5 GiB of pod memory requests + system pods do NOT fit on one 4 GiB node.
    combos=("Standard_B2s:${NODE_COUNT}" "Standard_D2s_v3:${NODE_COUNT}" "Standard_B2ms:1" "Standard_D4s_v3:1" "Standard_D2as_v5:${NODE_COUNT}")
  fi
  for c in "${combos[@]}"; do
    size="${c%%:*}"; count="${c##*:}"
    info "Creating AKS $AKS: ${count} x ${size} (takes 5-10 min)..."
    if az aks create -n "$AKS" -g "$RG" -l "$LOC" --node-count "$count" --node-vm-size "$size" \
         --generate-ssh-keys -o none; then
      info "AKS created with ${count} x ${size}."
      return 0
    fi
    warn "${count} x ${size} failed (quota/policy?). Trying next option..."
    az aks show -n "$AKS" -g "$RG" >/dev/null 2>&1 && az aks delete -n "$AKS" -g "$RG" --yes -o none 2>/dev/null || true
  done
  die "Could not create AKS. Check vCPU quota: az vm list-usage -l $LOC -o table"
}

ensure_aks() {
  AKS="$(first_name aks list)"; AKS="${AKS:-aks-aurabank}"
  if az aks show -n "$AKS" -g "$RG" >/dev/null 2>&1; then
    info "AKS exists: $AKS"
    if [ "$(az aks show -n "$AKS" -g "$RG" --query powerState.code -o tsv)" = "Stopped" ]; then
      info "Cluster is paused - starting it (2-3 min)..."
      az aks start -n "$AKS" -g "$RG" -o none
    fi
    POOL="$(az aks nodepool list --cluster-name "$AKS" -g "$RG" --query "[?mode=='System'] | [0].name" -o tsv)"
    [ -n "$POOL" ] || POOL="$(az aks nodepool list --cluster-name "$AKS" -g "$RG" --query "[0].name" -o tsv)"
    local cur; cur="$(az aks nodepool show --cluster-name "$AKS" -g "$RG" -n "$POOL" --query count -o tsv)"
    info "Node pool '$POOL': ${cur} node(s) of $(az aks nodepool show --cluster-name "$AKS" -g "$RG" -n "$POOL" --query vmSize -o tsv)"
    if [ "$cur" -lt "$NODE_COUNT" ]; then
      info "Scaling node pool ${cur} -> ${NODE_COUNT} so all pods fit..."
      az aks scale -n "$AKS" -g "$RG" --nodepool-name "$POOL" --node-count "$NODE_COUNT" -o none
    fi
  else
    create_aks
    POOL="$(az aks nodepool list --cluster-name "$AKS" -g "$RG" --query "[0].name" -o tsv)"
  fi
  az aks update -n "$AKS" -g "$RG" --attach-acr "$ACR" -o none 2>/dev/null || true
  az aks get-credentials -g "$RG" -n "$AKS" --overwrite-existing >/dev/null
  info "Waiting for nodes to be Ready..."
  kubectl wait --for=condition=Ready nodes --all --timeout=600s >/dev/null
  kubectl get nodes
}

scale_up_one() { # returns 1 when MAX_NODES reached
  local cur; cur="$(kubectl get nodes --no-headers | wc -l)"
  [ "$cur" -lt "$MAX_NODES" ] || return 1
  info "Adding a node: ${cur} -> $((cur + 1))"
  az aks scale -n "$AKS" -g "$RG" --nodepool-name "$POOL" --node-count "$((cur + 1))" -o none
  kubectl wait --for=condition=Ready nodes --all --timeout=600s >/dev/null
}

# ------------------------------------------------------------------------------
# 4. Cluster secrets
# ------------------------------------------------------------------------------
configure_cluster() {
  log "[4/8] Cluster secrets"
  kubectl create namespace banking --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  local acr_pass; acr_pass="$(az acr credential show -n "$ACR" --query "passwords[0].value" -o tsv)"
  kubectl create secret docker-registry acr-secret -n banking \
    --docker-server="${ACR}.azurecr.io" --docker-username="$ACR" --docker-password="$acr_pass" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  local i
  for i in $(seq 1 30); do kubectl get sa default -n banking >/dev/null 2>&1 && break; sleep 2; done
  kubectl patch serviceaccount default -n banking -p '{"imagePullSecrets":[{"name":"acr-secret"}]}' >/dev/null
  kubectl create secret generic banking-secrets -n banking \
    --from-literal=azure-sql-url="$SQL_JDBC_URL" \
    --from-literal=azure-sql-username="$SQL_ADMIN_USER" \
    --from-literal=azure-sql-password="$SQL_ADMIN_PASS" \
    --from-literal=azure-postgres-url="$PG_JDBC_URL" \
    --from-literal=azure-postgres-username="$PG_ADMIN_USER" \
    --from-literal=azure-postgres-password="$PG_ADMIN_PASS" \
    --from-literal=azure-storage-connection-string="$STORAGE_CONN" \
    --from-literal=jwt-secret="$JWT_SECRET" \
    --from-literal=datadog-api-key="$DATADOG_API_KEY" \
    --from-literal=datadog-site="$DATADOG_SITE" \
    --from-literal=interbank-api-key="$INTERBANK_API_KEY" \
    --from-literal=partner-bank-url="$PARTNER_BANK_URL" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  info "banking-secrets and acr-secret applied."
}

# ------------------------------------------------------------------------------
# 5. Images
# ------------------------------------------------------------------------------
image_exists() {
  local tags; tags="$(az acr repository show-tags -n "$ACR" --repository "$1" -o tsv 2>/dev/null || true)"
  [[ $'\n'"$tags"$'\n' == *$'\nlatest\n'* ]]
}

wanted_rebuild() { [ "$REBUILD" = "all" ] || [[ ",${REBUILD}," == *",$1,"* ]]; }

build_one() { # runs in a subshell
  local img="$1" df="$2" ctx="$3"
  if az acr build --registry "$ACR" --image "${img}:latest" --file "${ROOT}/${df}" "${ROOT}/${ctx}" \
       >"${LOG_DIR}/${img}.log" 2>&1; then
    touch "${LOG_DIR}/${img}.ok"
  fi
}

ensure_dd_agent() {
  # The Java Dockerfiles COPY backend/datadog/dd-java-agent.jar, which is not stored in git.
  local jar="${ROOT}/backend/datadog/dd-java-agent.jar"
  [ -s "$jar" ] && return 0
  info "Downloading Datadog Java agent (not stored in git)..."
  mkdir -p "${ROOT}/backend/datadog"
  if curl -fsSL --max-time 120 -o "$jar" https://dtdg.co/latest-java-tracer && [ "$(wc -c <"$jar")" -gt 1000000 ]; then
    info "Datadog agent ready."
  else
    rm -f "$jar"
    warn "Could not download the Datadog agent here; the Dockerfile will try to download it during the build."
  fi
}

build_images() {
  log "[5/8] Container images (only missing ones are built)"
  rm -f "${LOG_DIR}"/*.ok
  ensure_dd_agent
  BUILT=()
  local todo=() e img df ctx dep
  for e in "${IMAGES[@]}"; do
    IFS='|' read -r img df ctx dep <<<"$e"
    if wanted_rebuild "$img" || ! image_exists "$img"; then todo+=("$e"); else info "up to date: $img"; fi
  done
  [ "${#todo[@]}" -gt 0 ] || { info "All images already in ACR."; return 0; }

  info "Building ${#todo[@]} image(s), ${BUILD_PARALLEL} at a time. Logs: ${LOG_DIR}/<image>.log"
  for e in "${todo[@]}"; do
    IFS='|' read -r img df ctx dep <<<"$e"
    while [ "$(jobs -rp | wc -l)" -ge "$BUILD_PARALLEL" ]; do sleep 3; done
    info "start  : $img"
    build_one "$img" "$df" "$ctx" &
  done
  while [ "$(jobs -rp | wc -l)" -gt 0 ]; do sleep 20; echo "    ...still building ($(jobs -rp | wc -l) running)"; done
  wait || true

  local failed=0
  for e in "${todo[@]}"; do
    IFS='|' read -r img df ctx dep <<<"$e"
    if [ -f "${LOG_DIR}/${img}.ok" ]; then info "built  : $img"; BUILT+=("$dep")
    else
      failed=1; warn "FAILED : $img (last log lines below)"; tail -n 15 "${LOG_DIR}/${img}.log" | sed 's/^/      /' >&2
      grep -q "TasksOperationsNotAllowed" "${LOG_DIR}/${img}.log" 2>/dev/null && \
        warn "ACR Tasks are blocked on this subscription. Build locally with Docker: bash infrastructure/azure/build_and_push_images.sh ${ACR}"
    fi
  done
  [ "$failed" = "0" ] || die "Some images failed to build. Fix the error above and re-run (finished images are kept)."
}

# ------------------------------------------------------------------------------
# 6/7. Deploy + self-heal
# ------------------------------------------------------------------------------
deploy_workloads() {
  log "[6/8] Deploying workloads"
  kubectl apply -f "${K8S_DIR}/00_namespace.yaml" >/dev/null
  kubectl apply -f "${K8S_DIR}/00_in_cluster_dependencies.yaml" >/dev/null
  for d in redis postgres kafka mailhog; do
    kubectl rollout status deployment/"$d" -n banking --timeout=300s >/dev/null || warn "$d not ready yet"
  done
  info "In-cluster dependencies (redis, postgres, kafka, mailhog) are up."

  local f
  for f in "${K8S_DIR}"/0[1-9]*.yaml "${K8S_DIR}"/10*.yaml; do
    [ -f "$f" ] || continue
    sed "s/__ACR_NAME__/${ACR}/g" "$f" | kubectl apply -f - >/dev/null
    info "applied $(basename "$f")"
  done
  # Re-pull freshly built images (tag is :latest)
  local dep
  for dep in "${BUILT[@]:-}"; do
    [ -n "$dep" ] && kubectl rollout restart deployment/"$dep" -n banking >/dev/null 2>&1 || true
  done
  if [ "$RESET_DB" = "true" ]; then # fresh DB: let t24 (Flyway) migrate first, then the DB clients reconnect
    kubectl rollout restart deployment/t24-mock-cbs -n banking >/dev/null 2>&1 || true
    kubectl rollout status deployment/t24-mock-cbs -n banking --timeout=600s >/dev/null 2>&1 || true
    kubectl rollout restart deployment/account-service deployment/notification-service -n banking >/dev/null 2>&1 || true
  fi
}

wait_until_ready() {
  log "[7/8] Waiting for every pod to be Ready (auto-adds nodes if pods are Pending)"
  local deadline=$((SECONDS + READY_TIMEOUT)) strikes=0 notready unsched
  while (( SECONDS < deadline )); do
    notready="$(kubectl get deploy -n banking -o json | jq -r '.items[] | select((.status.readyReplicas // 0) < (.spec.replicas // 1)) | .metadata.name' | paste -sd' ' -)"
    if [ -z "$notready" ]; then info "All deployments are Ready."; return 0; fi

    unsched="$(kubectl get pods -n banking -o json | jq '[.items[] | select(.status.phase=="Pending") | select(any(.status.conditions[]?; .type=="PodScheduled" and .status=="False"))] | length')"
    if [ "${unsched:-0}" -gt 0 ]; then
      strikes=$((strikes + 1))
      info "${unsched} pod(s) cannot be scheduled (not enough node capacity) [${strikes}/3]"
      if [ "$strikes" -ge 3 ]; then
        scale_up_one || warn "Reached MAX_NODES=${MAX_NODES}; pods may stay Pending. Raise MAX_NODES or use a bigger AKS_VM_SIZE."
        strikes=0
      fi
    else
      strikes=0
      info "waiting for: ${notready}"
    fi
    sleep 20
  done

  warn "Timed out. Current pods:"
  kubectl get pods -n banking -o wide || true
  local p
  for p in $(kubectl get pods -n banking -o json | jq -r '.items[] | select(.status.phase!="Succeeded") | select(.status.phase=="Pending" or any(.status.containerStatuses[]?; .ready==false)) | .metadata.name' | head -n 4); do
    echo "---- $p ----"; kubectl describe pod "$p" -n banking | tail -n 12
    kubectl logs "$p" -n banking --tail=15 2>/dev/null || true
  done
  return 1
}

# ------------------------------------------------------------------------------
# 8. Endpoints + smoke tests
# ------------------------------------------------------------------------------
svc_ip() { # svc_ip <service>  (waits up to 5 min for the public IP)
  local ip="" i
  for i in $(seq 1 30); do
    ip="$(kubectl get svc "$1" -n banking -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
    [ -n "$ip" ] && break; sleep 10
  done
  printf '%s' "$ip"
}

http_code() { # http_code <curl args...>
  curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$@" 2>/dev/null || true
}

expect() { # expect <label> <regex> <curl args...>  (retries ~2 min)
  local label="$1" re="$2" code="" i; shift 2
  for i in $(seq 1 12); do
    code="$(http_code "$@")"
    [[ "$code" =~ $re ]] && { printf '    [PASS] %-34s HTTP %s\n' "$label" "$code"; return 0; }
    sleep 10
  done
  printf '    [FAIL] %-34s HTTP %s (expected %s)\n' "$label" "${code:-000}" "$re"
  return 1
}

smoke_test() {
  log "[8/8] Endpoints & smoke tests"
  GW_IP="$(svc_ip gateway-service)"; STAFF_IP="$(svc_ip banking-frontend)"; WEB_IP="$(svc_ip aurabank-customer-web)"
  [ -n "$GW_IP" ] || die "Gateway has no public IP yet. Re-run in a minute: bash infrastructure/azure/run_azure.sh status"
  local fails=0
  expect "gateway /actuator/health"          '^(200|401|403)$' "http://${GW_IP}:8080/actuator/health" || fails=1
  # A wrong password must answer 4xx. A 5xx/000 means gateway->account-service->Azure SQL is broken.
  expect "login path (gateway->account->SQL)" '^(400|401|403|404|422|429)$' -X POST \
         -H 'Content-Type: application/json' -d '{"email":"smoke@test.invalid","password":"x"}' \
         "http://${GW_IP}:8080/api/v1/auth/login" || {
    fails=1
    warn "Login path failed. The tables are created by t24-mock-cbs (Flyway). Check it:"
    echo "      kubectl logs deploy/t24-mock-cbs -n banking | grep -iE 'flyway|migrat|error' | tail -n 20"
    echo "      If it says tables already exist from an earlier failed run:  RESET_DB=true bash infrastructure/azure/run_azure.sh"
  }
  if [ -n "$STAFF_IP" ]; then expect "staff console" '^200$' "http://${STAFF_IP}/" || fails=1
  else echo "    [FAIL] staff console has no public IP (public IP quota?)"; fails=1; fi
  if [ -n "$WEB_IP" ]; then expect "customer web portal" '^200$' "http://${WEB_IP}/" || fails=1
  else echo "    [FAIL] customer web portal has no public IP (public IP quota?)"; fails=1; fi
  return "$fails"
}

print_summary() {
  local out="${HOME:-/tmp}/aurabank-endpoints.txt"
  {
    echo "===================================================================="
    echo " AuraBank on Azure  |  RG: $RG  |  AKS: $AKS  |  ACR: $ACR"
    echo "===================================================================="
    echo " Staff Admin Console : http://${STAFF_IP:-<pending>}"
    echo " Customer Web Portal : http://${WEB_IP:-<pending>}"
    echo " API Gateway         : http://${GW_IP:-<pending>}:8080"
    echo ""
    echo " MOBILE APP: Login screen -> server icon (top right) -> select"
    echo "   'Azure Cloud' -> paste  http://${GW_IP:-<gateway-ip>}:8080  -> Save & Close"
    echo ""
    echo " Mail inbox (OTP emails): kubectl port-forward svc/mailhog 8025:8025 -n banking"
    echo "                          then open http://localhost:8025"
    echo " Save money when idle   : bash infrastructure/azure/pause_budget_azure.sh"
    echo " Resume                 : bash infrastructure/azure/run_azure.sh   (starts the cluster)"
    echo "===================================================================="
  } | tee "$out"
}

# ------------------------------------------------------------------------------
# main
# ------------------------------------------------------------------------------
main() {
  preflight
  log "[2/8] Azure infrastructure"
  pick_resource_group

  AKS="$(first_name aks list)"
  if [ "$MODE" = "status" ]; then
    [ -n "$AKS" ] || die "No AKS cluster found in $RG."
    az aks get-credentials -g "$RG" -n "$AKS" --overwrite-existing >/dev/null
    ACR="$(first_name acr list)"; STORAGE=""
    kubectl get nodes; kubectl get pods -n banking -o wide
    GW_IP="$(svc_ip gateway-service)"; STAFF_IP="$(svc_ip banking-frontend)"; WEB_IP="$(svc_ip aurabank-customer-web)"
    print_summary; return 0
  fi

  SQL_ADMIN_PASS="${SQL_ADMIN_PASS:-}"
  PG_ADMIN_PASS="${PG_ADMIN_PASS:-}"
  if [ -z "$SQL_ADMIN_PASS" ] && [ -n "$AKS" ]; then SQL_ADMIN_PASS="$(load_existing_secret "$AKS")"; fi
  if [ -z "$PG_ADMIN_PASS" ] && [ -n "$AKS" ]; then
    PG_ADMIN_PASS="$(kubectl get secret banking-secrets -n banking -o jsonpath='{.data.azure-postgres-password}' --request-timeout=15s 2>/dev/null | base64 -d 2>/dev/null || true)"
  fi
  if [ -z "$DATADOG_API_KEY" ] && [ -n "$AKS" ]; then
    DATADOG_API_KEY="$(kubectl get secret banking-secrets -n banking -o jsonpath='{.data.datadog-api-key}' --request-timeout=15s 2>/dev/null | base64 -d 2>/dev/null || true)"
  fi
  if [ -z "$INTERBANK_API_KEY" ] && [ -n "$AKS" ]; then
    INTERBANK_API_KEY="$(kubectl get secret banking-secrets -n banking -o jsonpath='{.data.interbank-api-key}' --request-timeout=15s 2>/dev/null | base64 -d 2>/dev/null || true)"
  fi
  if [ -z "$PARTNER_BANK_URL" ] && [ -n "$AKS" ]; then
    PARTNER_BANK_URL="$(kubectl get secret banking-secrets -n banking -o jsonpath='{.data.partner-bank-url}' --request-timeout=15s 2>/dev/null | base64 -d 2>/dev/null || true)"
  fi
  SQL_ADMIN_PASS="${SQL_ADMIN_PASS:-$(gen_pass)}"
  PG_ADMIN_PASS="${PG_ADMIN_PASS:-$(gen_pass)}"
  DATADOG_API_KEY="${DATADOG_API_KEY:-00000000000000000000000000000000}"
  INTERBANK_API_KEY="${INTERBANK_API_KEY:-AURABANK-SECURE-KEY-2026}"
  PARTNER_BANK_URL="${PARTNER_BANK_URL:-http://transfer-orchestrator.banking.svc.cluster.local:8082/api/v1/mock-partner/transfers}"

  ensure_sql
  ensure_storage
  ensure_acr

  log "[3/8] AKS cluster"
  ensure_aks
  configure_cluster
  build_images
  deploy_workloads
  local ready=0 smoke=0
  wait_until_ready || ready=1
  smoke_test || smoke=1
  print_summary

  if [ "$ready" = "0" ] && [ "$smoke" = "0" ]; then
    echo -e "\n\033[1;32mSUCCESS: every service is Running and the login path works.\033[0m"
  else
    echo -e "\n\033[1;31mNOT HEALTHY YET.\033[0m Re-run this script (it only fixes what is broken), or inspect:"
    echo "  kubectl get pods -n banking ; kubectl logs deploy/account-service -n banking --tail=50"
    exit 1
  fi
}

main "$@"
