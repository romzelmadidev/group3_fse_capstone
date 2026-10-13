# AuraBank Azure Cloud Deployment Guide

This directory contains the production-ready deployment scripts and manifests for the **AuraBank Retail Banking Platform** on Microsoft Azure, strictly adhering to the **\$10.00 USD total budget constraint**, maintaining **zero breaking changes to local development**, and providing **Datadog APM tracing** and real-time **Server-Sent Events (SSE)**.

---

## 1. Quick Start: Deployment Options

### Option A: Azure Cloud Shell (Split Scripts — Timeout-Proof)
Azure Cloud Shell terminates sessions after 20 minutes of inactivity. To prevent timeouts, use the modular split scripts that each run in 2 to 5 minutes and save state to `azure_deployment_env.sh`:

```bash
# 1. Open Azure Cloud Shell (https://shell.azure.com) in Bash mode
git clone <YOUR_GIT_REPO_URL> group3_fse_capstone
cd group3_fse_capstone
chmod +x infrastructure/azure/*.sh

# 2. Run the interactive step-by-step menu:
bash infrastructure/azure/deploy_step_by_step.sh
```

Or execute each step individually:
1. `bash infrastructure/azure/step1_infra_provision.sh` (~3-5m: SQL, Storage, ACR, AKS)
2. `bash infrastructure/azure/step2a_build_backend.sh` (~4-6m: 6 Java microservices)
3. `bash infrastructure/azure/step2b_build_risk.sh` (~2-3m: Python risk-service)
4. `bash infrastructure/azure/step2c_build_frontends.sh` (~3-5m: React & Flutter Web)
5. `bash infrastructure/azure/step3_k8s_deploy.sh` (~2m: Secrets, pods, and deployments)
6. `bash infrastructure/azure/step4_check_endpoints.sh` (~10s: Live public URLs)

*(If Cloud Shell disconnects at any point, simply reconnect and run the next step. No state is lost!)*

---

### Option B: Local Windows Machine via Native PowerShell (Fastest)
Deploy directly from your Windows machine using local Azure CLI, local Docker Desktop, and local `kubectl`:

```powershell
# 1. In PowerShell, ensure Docker Desktop is running and log in to Azure
az login

# 2. Run the master deployment script from the repository root:
.\infrastructure\azure\deploy_all_azure.ps1
```

---

## 2. File and Script Catalog

| Script / File | Purpose |
| :--- | :--- |
| **`deploy_step_by_step.sh`** | Interactive menu runner for Azure Cloud Shell. |
| **`step1_infra_provision.sh`** | Provisions Azure SQL (Basic 5 DTU, LRS), Storage Account (`compliance-vault`), ACR, and AKS (`Standard_B2s` candidate loop). Generates `azure_deployment_env.sh`. |
| **`step2a_build_backend.sh`** | Builds and pushes 6 Java microservices (`gateway`, `account`, `transfer-orchestrator`, `t24`, `compliance`, `notification`). |
| **`step2b_build_risk.sh`** | Builds and pushes Python `risk-service` with Datadog agent base and XGBoost. |
| **`step2c_build_frontends.sh`** | Builds and pushes Staff React Console (`banking-frontend`) and Customer Portal (`aurabank-web`). |
| **`step3_k8s_deploy.sh`** | Configures `kubectl`, injects `banking-secrets` into AKS, deploys in-cluster dependencies, and initiates rolling rollout. |
| **`step4_check_endpoints.sh`** | Inspects pod health and displays live public LoadBalancer IP endpoints. |
| **`deploy_all_azure.ps1`** | Single-command automated runner for local Windows PowerShell. |
| **`pause_budget_azure.ps1` / `.sh`** | Stops AKS cluster nodes when not in use to preserve the \$10 budget (~$0.21/day). |
| **`resume_budget_azure.ps1` / `.sh`** | Resumes AKS cluster compute before demos or testing (~2 minutes). |
| **`01_azure_sql_master_schema.sql`** | Full T-SQL master relational schema with `admin_users`, views, and `dbo.*` synonyms. |
| **`02_azure_postgres_audit_schema.sql`**| Append-only PostgreSQL audit vault schema with immutability triggers. |

---

## 3. Environment Variables Reference

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `DATADOG_API_KEY` | `00000000...` *(dummy)* | Set to your live Datadog API key to send traces to your Datadog dashboard. |
| `PARTNER_BANK_URL` | In-cluster mock URL | Target endpoint for outward inter-bank transfers. Can point to another team's API. |
| `PARTNER_BANK_API_KEY`| `AURABANK-SECURE-KEY-2026` | Shared secret header sent during interbank transfers. |
| `RESOURCE_GROUP` | Auto-detected / `rg-banking-budget-$LOCATION` | Name of your Azure Resource Group (auto-detects student sandboxes). |
| `AKS_VM_SIZE` | `Standard_B2s` | Memory-optimized budget VM size allowed in student sandboxes. |
| `CBS_COB_SCHEDULER_ENABLED` | `false` | Disables in-app Spring cron in AKS so only the Kubernetes CronJob runs COB cutoff at 21:00 PHT. |

---

## 4. Cost Management (\$10 Budget Preservation)

- **Between Presentations**: Run `pause_budget_azure.ps1` (or `pause_budget_azure.sh`) to deallocate the AKS node VM. Spend drops to ~\$0.21/day.
- **Before Testing**: Run `resume_budget_azure.ps1` (or `resume_budget_azure.sh`) to start the node back up.
- **Permanent Teardown**: Run `az group delete --name "$RESOURCE_GROUP" --yes --no-wait`.
