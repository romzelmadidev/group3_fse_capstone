<#
.SYNOPSIS
    Resumes AKS cluster compute before testing or presenting.
#>
[CmdletBinding()]
param(
    [string]$ResourceGroup = "",
    [string]$AksClusterName = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ResourceGroup)) {
    $ResourceGroup = az group list --query "[?name!='NetworkWatcherRG' && !starts_with(name, 'cloud-shell-storage')].name | [0]" -o tsv 2>$null
    if (-not $ResourceGroup) {
        $ResourceGroup = "rg-banking-budget-southeastasia"
    }
}

if ([string]::IsNullOrWhiteSpace($AksClusterName)) {
    $AksClusterName = az aks list --resource-group $ResourceGroup --query "[0].name" -o tsv 2>$null
    if (-not $AksClusterName) {
        $AksClusterName = "aks-banking-budget"
    }
}

Write-Host "Resuming AKS cluster '$AksClusterName' in resource group '$ResourceGroup'..." -ForegroundColor Yellow
az aks start --name $AksClusterName --resource-group $ResourceGroup --no-wait
Write-Host "AKS resume initiated! Nodes will be ready in ~2 minutes." -ForegroundColor Green
Write-Host "Check pod status: kubectl get pods -n banking" -ForegroundColor Cyan
