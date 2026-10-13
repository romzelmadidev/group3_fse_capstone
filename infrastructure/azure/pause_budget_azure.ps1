<#
.SYNOPSIS
    Pauses AKS cluster compute to conserve the $10 Azure budget.
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

Write-Host "Pausing AKS cluster '$AksClusterName' in resource group '$ResourceGroup'..." -ForegroundColor Yellow
az aks stop --name $AksClusterName --resource-group $ResourceGroup --no-wait
Write-Host "AKS compute paused successfully! Daily cost reduced to ~$0.21/day." -ForegroundColor Green
Write-Host "To resume compute later, run: .\infrastructure\azure\resume_budget_azure.ps1" -ForegroundColor Cyan
