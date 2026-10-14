<#
.SYNOPSIS
    AuraBank Core Banking Platform - Native Windows PowerShell Azure Deployment Runner.
.DESCRIPTION
    Automates end-to-end cloud provisioning, local Docker container builds, ACR image pushing,
    secret synchronization, and Kubernetes rollout using local Azure CLI, Docker, and kubectl.
.PARAMETER ResourceGroup
    Azure Resource Group name. Defaults to auto-detecting an assigned sandbox group.
.PARAMETER Location
    Azure Region. Defaults to 'southeastasia' or detected resource group location.
.PARAMETER DatadogApiKey
    Datadog API key for APM distributed tracing. Defaults to a dummy key.
.PARAMETER AksVmSize
    AKS Node VM Size. Defaults to 'Standard_B2s' (supported in Azure student sandboxes).
#>
[CmdletBinding()]
param(
    [string]$ResourceGroup = "",
    [string]$Location = "southeastasia",
    [string]$DatadogApiKey = "00000000000000000000000000000000",
    [string]$DatadogSite = "datadoghq.com",
    [string]$InterbankApiKey = "AURABANK-SECURE-KEY-2026",
    [string]$PartnerBankUrl = "http://transfer-orchestrator.banking.svc.cluster.local:8082/api/v1/mock-partner/transfers",
    [string]$AksVmSize = "Standard_B2s",
    [bool]$UseManagedPostgres = $false
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir = (Resolve-Path "$ScriptDir\..\..").Path

Write-Host "====================================================================" -ForegroundColor Cyan
Write-Host "   AuraBank Core Banking Platform - Local Azure Deployment Runner   " -ForegroundColor Cyan
Write-Host "====================================================================" -ForegroundColor Cyan
Write-Host "Working Directory: $RootDir"
Write-Host "Current Time:      $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""

# ==============================================================================
# Phase 1: Verify Local Prerequisites (az, docker, kubectl)
# ==============================================================================
Write-Host "[Phase 1/4] Verifying Local CLI Prerequisites..." -ForegroundColor Yellow

# 1. Check Azure CLI
if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Azure CLI ('az') is not installed or not in your PATH." -ForegroundColor Red
    Write-Host "To install Azure CLI on Windows, run this in PowerShell:" -ForegroundColor Yellow
    Write-Host "  Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi; Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'; Remove-Item .\AzureCLI.msi" -ForegroundColor Green
    Write-Host "After installation, open a new PowerShell terminal and re-run this script." -ForegroundColor Yellow
    exit 1
}

# 2. Check Docker
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Docker is not installed or not in your PATH." -ForegroundColor Red
    exit 1
}
try {
    docker info > $null 2>&1
    Write-Host "Local Docker daemon is running." -ForegroundColor Green
} catch {
    Write-Host "Error: Docker daemon is not running. Please start Docker Desktop." -ForegroundColor Red
    exit 1
}

# 3. Check kubectl
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Host "Error: kubectl is not installed or not in your PATH." -ForegroundColor Red
    exit 1
}

# 4. Check Azure Login
$accountInfo = az account show -o json 2>$null | ConvertFrom-Json
if (-not $accountInfo) {
    Write-Host "You are not logged into Azure CLI. Launching 'az login'..." -ForegroundColor Yellow
    az login -o table
    $accountInfo = az account show -o json | ConvertFrom-Json
}
Write-Host "Active Subscription: $($accountInfo.name) ($($accountInfo.id))" -ForegroundColor Green

# ==============================================================================
# Phase 2: Detect Environment & Cloud Provisioning
# ==============================================================================
Write-Host ""
Write-Host "[Phase 2/4] Provisioning Azure Cloud Infrastructure..." -ForegroundColor Yellow

$RandomSuffix = (Get-Date -UFormat "%s").Substring((Get-Date -UFormat "%s").Length - 5)

# Detect Resource Group
if ([string]::IsNullOrWhiteSpace($ResourceGroup)) {
    $detectedRg = az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name, 'cloud-shell-storage')].name | [0]" -o tsv 2>$null
    if ($detectedRg -and $detectedRg -ne "None") {
        $ResourceGroup = $detectedRg.Trim()
        Write-Host "Detected Sandbox Resource Group: $ResourceGroup" -ForegroundColor Cyan
        $detectedLoc = az group show --name $ResourceGroup --query location -o tsv 2>$null
        if ($detectedLoc -and $detectedLoc -ne "None") {
            $Location = $detectedLoc.Trim()
        }
    } else {
        $ResourceGroup = "rg-banking-budget-$Location"
        Write-Host "Creating Resource Group: $ResourceGroup ($Location)..." -ForegroundColor Cyan
        az group create --name $ResourceGroup --location $Location -o table
    }
}

# Detect or name resources
$existingAcr = az acr list --resource-group $ResourceGroup --query "[0].name" -o tsv 2>$null
if ($existingAcr -and $existingAcr -ne "None") {
    $AcrName = $existingAcr.Trim()
    Write-Host "Detected existing ACR: $AcrName" -ForegroundColor Cyan
} else {
    $AcrName = "acrbanking$RandomSuffix"
}

$existingAks = az aks list --resource-group $ResourceGroup --query "[0].name" -o tsv 2>$null
if ($existingAks -and $existingAks -ne "None") {
    $AksName = $existingAks.Trim()
    Write-Host "Detected existing AKS Cluster: $AksName" -ForegroundColor Cyan
} else {
    $AksName = "aks-banking-budget"
}

$existingSql = az sql server list --resource-group $ResourceGroup --query "[0].name" -o tsv 2>$null
if ($existingSql -and $existingSql -ne "None") {
    $SqlServerName = $existingSql.Trim()
    Write-Host "Detected existing Azure SQL Server: $SqlServerName" -ForegroundColor Cyan
} else {
    $SqlServerName = "sql-banking-$RandomSuffix"
}

$SqlDbName = "sqldb-master"
$SqlAdminUser = "sqladminuser"
$SqlAdminPass = "Capst0ne!Secure$RandomSuffix#"

$PgServerName = "psql-banking-$RandomSuffix"
$PgDbName = "banking_audit"
$PgAdminUser = "audit_admin"
$PgAdminPass = "Audit!Vault$RandomSuffix#"

$JwtSecret = "c3VwZXItc2VjcmV0LWtleS1mb3ItZnNlLWNhcHN0b25lLWJhbmtpbmctcGxhdGZvcm0tMjAyNi0xMjM0NTY3ODkwMTI="

# Provision Azure SQL
Write-Host "Configuring Azure SQL Server & Basic 5 DTU Database..." -ForegroundColor Cyan
$sqlServerExists = az sql server show --name $SqlServerName --resource-group $ResourceGroup 2>$null
if (-not $sqlServerExists) {
    az sql server create --name $SqlServerName --resource-group $ResourceGroup --location $Location --admin-user $SqlAdminUser --admin-password $SqlAdminPass -o table
    az sql server firewall-rule create --server $SqlServerName --resource-group $ResourceGroup --name "AllowAzureInternal" --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0 -o table
    az sql db create --server $SqlServerName --resource-group $ResourceGroup --name $SqlDbName --edition Basic --service-objective Basic --capacity 5 --backup-storage-redundancy Local --yes -o table
} else {
    az sql server update --name $SqlServerName --resource-group $ResourceGroup --admin-password $SqlAdminPass -o none 2>$null
}

$SqlFqdn = "$SqlServerName.database.windows.net"
$SqlJdbcUrl = "jdbc:sqlserver://${SqlFqdn}:1433;databaseName=${SqlDbName};encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30;"

# PostgreSQL Mode
if ($UseManagedPostgres) {
    Write-Host "Provisioning Azure Database for PostgreSQL Flexible Server..." -ForegroundColor Cyan
    az postgres flexible-server create --name $PgServerName --resource-group $ResourceGroup --location $Location --admin-user $PgAdminUser --admin-password $PgAdminPass --sku-name Standard_B1ms --tier Burstable --version 16 --storage-size 32 --yes -o table
    az postgres flexible-server firewall-rule create --name $PgServerName --resource-group $ResourceGroup --rule-name "AllowAzureInternal" --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0 -o table
    az postgres flexible-server db create --server-name $PgServerName --resource-group $ResourceGroup --database-name $PgDbName -o table
    $PgFqdn = "$PgServerName.postgres.database.azure.com"
    $PgJdbcUrl = "jdbc:postgresql://${PgFqdn}:5432/${PgDbName}?sslmode=require"
} else {
    Write-Host "PostgreSQL Mode: In-Cluster Pod (Free, zero extra cost)." -ForegroundColor Cyan
    $PgFqdn = "postgres.banking.svc.cluster.local"
    $PgJdbcUrl = "jdbc:postgresql://${PgFqdn}:5432/${PgDbName}?sslmode=disable"
}

# Provision Azure Storage Account
$existingSa = az storage account list --resource-group $ResourceGroup --query "[0].name" -o tsv 2>$null
if ($existingSa -and $existingSa -ne "None") {
    $StorageAccountName = $existingSa.Trim()
} else {
    $StorageAccountName = "stbanking$RandomSuffix"
    Write-Host "Provisioning Storage Account: $StorageAccountName..." -ForegroundColor Cyan
    az storage account create --name $StorageAccountName --resource-group $ResourceGroup --location $Location --sku Standard_LRS --kind StorageV2 -o table
}
$StorageConnStr = az storage account show-connection-string --name $StorageAccountName --resource-group $ResourceGroup --query "connectionString" -o tsv
az storage container create --name "compliance-vault" --connection-string $StorageConnStr 2>$null

# Provision Azure Container Registry
$acrExists = az acr show --name $AcrName --resource-group $ResourceGroup 2>$null
if (-not $acrExists) {
    Write-Host "Provisioning Azure Container Registry: $AcrName..." -ForegroundColor Cyan
    az acr create --name $AcrName --resource-group $ResourceGroup --sku Basic --admin-enabled true -o table
}

# Provision Single-Node AKS Cluster
$aksExists = az aks show --name $AksName --resource-group $ResourceGroup 2>$null
if (-not $aksExists) {
    Write-Host "Provisioning Single-Node AKS Cluster: $AksName..." -ForegroundColor Cyan
    az provider register --namespace Microsoft.ContainerService 2>$null
    
    $created = $false
    $candidateSizes = @($AksVmSize, "Standard_B2s", "Standard_D2s_v3", "Standard_DS2_v2")
    foreach ($vm in $candidateSizes) {
        Write-Host "Attempting AKS creation with VM size: $vm..." -ForegroundColor Cyan
        try {
            az aks create --name $AksName --resource-group $ResourceGroup --location $Location --node-count 1 --node-vm-size $vm --generate-ssh-keys -o table
            $created = $true
            Write-Host "AKS Cluster $AksName successfully provisioned with $vm." -ForegroundColor Green
            break
        } catch {
            Write-Host "[NOTICE] VM size $vm failed or restricted in sandbox. Trying next candidate..." -ForegroundColor Yellow
        }
    }
    if (-not $created) {
        Write-Host "Error: Unable to provision AKS cluster in $ResourceGroup." -ForegroundColor Red
        az aks list --resource-group $ResourceGroup -o table 2>$null
        exit 1
    }
} else {
    Write-Host "Using existing AKS Cluster: $AksName..." -ForegroundColor Green
}

az aks update -n $AksName -g $ResourceGroup --attach-acr $AcrName 2>$null

# ==============================================================================
# Phase 3: Local Docker Build and Push to ACR
# ==============================================================================
Write-Host ""
Write-Host "[Phase 3/4] Building and Pushing Container Images using Local Docker..." -ForegroundColor Yellow

$AcrLoginServer = "$AcrName.azurecr.io"
Write-Host "Logging into Azure Container Registry: $AcrLoginServer..." -ForegroundColor Cyan
az acr login --name $AcrName

# 1. Java Microservices (built from backend/ context)
$JavaServices = @(
    "gateway-service",
    "account-service",
    "transfer-orchestrator",
    "t24-mock-cbs",
    "compliance-service",
    "notification-service"
)

foreach ($svc in $JavaServices) {
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
    Write-Host "Building & Pushing Java service: $svc..." -ForegroundColor Cyan
    $dockerfile = "$RootDir\backend\$svc\Dockerfile"
    $imageTag = "$AcrLoginServer/${svc}:latest"
    docker build -f $dockerfile -t $imageTag "$RootDir\backend"
    docker push $imageTag
}

# 2. Python Risk Service
Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
Write-Host "Building & Pushing Python service: risk-service..." -ForegroundColor Cyan
$riskTag = "$AcrLoginServer/risk-service:latest"
docker build -f "$RootDir\backend\risk-service\Dockerfile" -t $riskTag "$RootDir\backend\risk-service"
docker push $riskTag

# 3. React Staff Frontend SPA
Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
Write-Host "Building & Pushing Staff Frontend: banking-frontend..." -ForegroundColor Cyan
$frontendTag = "$AcrLoginServer/banking-frontend:latest"
docker build -f "$RootDir\frontend\Dockerfile" -t $frontendTag "$RootDir\frontend"
docker push $frontendTag

# 4. Flutter Customer Retail Web Portal (built from repository root context)
Write-Host "------------------------------------------------------------" -ForegroundColor DarkCyan
Write-Host "Building & Pushing Customer Web Portal: aurabank-web..." -ForegroundColor Cyan
$auraTag = "$AcrLoginServer/aurabank-web:latest"
docker build -f "$RootDir\aurabank_web\Dockerfile" -t $auraTag "$RootDir"
docker push $auraTag

Write-Host "All container images successfully built and pushed to $AcrLoginServer." -ForegroundColor Green

# ==============================================================================
# Phase 4: Configure Kubernetes & Rollout
# ==============================================================================
Write-Host ""
Write-Host "[Phase 4/4] Configuring Kubernetes Cluster Credentials & Deployments..." -ForegroundColor Yellow

az aks get-credentials --resource-group $ResourceGroup --name $AksName --overwrite-existing

kubectl create namespace banking --dry-run=client -o yaml | kubectl apply -f -

# ACR Image Pull Secret for sandbox resilience
$acrPass = az acr credential show --name $AcrName --query "passwords[0].value" -o tsv 2>$null
if ($acrPass) {
    kubectl create secret docker-registry acr-secret `
        --namespace banking `
        --docker-server="$AcrLoginServer" `
        --docker-username="$AcrName" `
        --docker-password="$acrPass" `
        --dry-run=client -o yaml | kubectl apply -f -
    kubectl patch serviceaccount default -n banking -p '{"imagePullSecrets": [{"name": "acr-secret"}]}' 2>$null
}

# Inject authoritative secrets into banking-secrets
Write-Host "Configuring credentials in Kubernetes secret (banking-secrets)..." -ForegroundColor Cyan
kubectl create secret generic banking-secrets `
    --namespace banking `
    --from-literal=azure-sql-url="$SqlJdbcUrl" `
    --from-literal=azure-sql-username="$SqlAdminUser" `
    --from-literal=azure-sql-password="$SqlAdminPass" `
    --from-literal=azure-postgres-url="$PgJdbcUrl" `
    --from-literal=azure-postgres-username="$PgAdminUser" `
    --from-literal=azure-postgres-password="$PgAdminPass" `
    --from-literal=azure-storage-connection-string="$StorageConnStr" `
    --from-literal=jwt-secret="$JwtSecret" `
    --from-literal=datadog-api-key="$DatadogApiKey" `
    --from-literal=datadog-site="$DatadogSite" `
    --from-literal=interbank-api-key="$InterbankApiKey" `
    --from-literal=partner-bank-url="$PartnerBankUrl" `
    --dry-run=client -o yaml | kubectl apply -f -

# Apply all manifests with ACR substitution
$K8sDir = "$RootDir\infrastructure\k8s"
kubectl apply -f "$K8sDir\00_namespace.yaml"
kubectl apply -f "$K8sDir\00_in_cluster_dependencies.yaml"

$manifests = Get-ChildItem "$K8sDir" -Filter "*.yaml" | Where-Object { $_.Name -match "^(0[1-9]|10).*\.yaml$" }
foreach ($m in $manifests) {
    Write-Host "Deploying manifest: $($m.Name)..." -ForegroundColor Cyan
    $content = (Get-Content $m.FullName -Raw) -replace "__ACR_NAME__", $AcrName
    $content | kubectl apply -f -
}

Write-Host "Triggering rolling deployment update..." -ForegroundColor Cyan
kubectl rollout restart deployment -n banking

Write-Host ""
Write-Host "====================================================================" -ForegroundColor Green
Write-Host "                    DEPLOYMENT COMPLETE!                            " -ForegroundColor Green
Write-Host "====================================================================" -ForegroundColor Green
Write-Host " Resource Group:       $ResourceGroup"
Write-Host " AKS Cluster:          $AksName"
Write-Host " ACR Registry:         $AcrLoginServer"
Write-Host " Azure SQL Host:       $SqlFqdn"
Write-Host " Azure SQL DB:         $SqlDbName (User: $SqlAdminUser, Pass: $SqlAdminPass)"
Write-Host " Storage Account:      $StorageAccountName"
Write-Host "====================================================================" -ForegroundColor Green
Write-Host ""
Write-Host "Waiting for Public Load Balancer IPs to be assigned (this takes ~1-2 minutes)..." -ForegroundColor Yellow
Write-Host "To monitor service endpoints, run:"
Write-Host "  kubectl get svc -n banking -w"
Write-Host ""
Write-Host "Endpoints to check:"
Write-Host "  1. Staff Admin Console:       kubectl get svc banking-frontend -n banking"
Write-Host "  2. Customer Retail Portal:    kubectl get svc aurabank-customer-web -n banking"
Write-Host "  3. API Gateway:               kubectl get svc gateway-service -n banking"
Write-Host "  4. MailHog Web Inbox:         kubectl port-forward svc/mailhog 8025:8025 -n banking"
Write-Host "====================================================================" -ForegroundColor Green
