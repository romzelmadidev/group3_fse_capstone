# AuraBank Cloud Deployment: Execution Guide & Environment Configuration

This guide details the complete deployment workflow for the AuraBank retail banking platform on Microsoft Azure, adhering strictly to the **\$10.00 USD total budget constraint**, preserving **zero breaking changes to local development**, and delivering high observability with **Datadog APM** and real-time **Server-Sent Events (SSE)**.

---

## 1. How to Transfer the Code and Scripts to Azure

To execute the deployment scripts against your Azure subscription, you have two primary methods:

### Option A: Azure Cloud Shell (Split Scripts — Timeout-Proof)
Azure Cloud Shell provides a browser-based Bash terminal pre-authenticated with Azure CLI, Docker (`az acr build`), and `kubectl`.

1. **Commit and push** your repository to your remote Git hosting (e.g., GitHub, Azure DevOps, GitLab):
   ```bash
   git add .
   git commit -m "feat(azure): complete automated deployment modernization"
   git push origin main
   ```
2. Open your browser and navigate to [https://shell.azure.com](https://shell.azure.com).
3. Select **Bash** environment.
4. Clone the repository directly into Cloud Shell:
   ```bash
   git clone <YOUR_GIT_REPO_URL> group3_fse_capstone
   cd group3_fse_capstone
   chmod +x infrastructure/azure/*.sh
   ```

### Fully Session-Independent Cloud Shell Execution:
Azure Cloud Shell terminates sessions after 20 minutes of inactivity. To make deployment completely timeout-proof and session-resilient:
- **Session-Agnostic Resolution**: Every script uses `load_azure_env.sh` to auto-discover your Azure resources (Resource Group, ACR, AKS, SQL, Secrets) directly via Azure CLI. **You DO NOT need a single persistent session**. If a session times out, simply reopen Cloud Shell and run the next step.
- **Concurrent Multi-Tab Execution**: You can open multiple Cloud Shell browser tabs to run build steps in parallel (e.g., Tab 1 for `step2a_build_backend.sh`, Tab 2 for `step2b_build_risk.sh`, Tab 3 for `step2c_build_frontends.sh`).
- **Selective Single-Service Builds**: If you modify or need to rebuild a single service, you don't need to rebuild everything:
  - `bash infrastructure/azure/step2a_build_backend.sh gateway-service` (or `account-service`, `transfer-orchestrator`, etc.)
  - `bash infrastructure/azure/step2c_build_frontends.sh banking-frontend` (or `aurabank-web`)

- **Interactive Menu Runner**:
  ```bash
  bash infrastructure/azure/deploy_step_by_step.sh
  ```
- **Or Run the Scripts One-by-One in Any Session**:
  - `bash infrastructure/azure/step1_infra_provision.sh` (~3-5m: SQL, Storage, ACR, AKS)
  - `bash infrastructure/azure/step2a_build_backend.sh [service-name]` (~4-6m: 6 Java microservices, or single target)
  - `bash infrastructure/azure/step2b_build_risk.sh` (~2-3m: Python risk-service)
  - `bash infrastructure/azure/step2c_build_frontends.sh [target]` (~3-5m: React & Flutter Web)
  - `bash infrastructure/azure/step3_k8s_deploy.sh` (~2m: Secrets, pods, and deployments)
  - `bash infrastructure/azure/step4_check_endpoints.sh` (~10s: Live public URLs)

*(If your Cloud Shell session disconnects at any point, simply reconnect and run the next step. No work is lost!)*

---

### Option B: Local Machine via Native PowerShell (Windows)
If deploying directly from your Windows machine:

1. **Install Azure CLI** (if not already installed):
   Run in PowerShell:
   ```powershell
   Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi; Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'; Remove-Item .\AzureCLI.msi
   ```
   *(Close and reopen PowerShell after installation).*

2. **Verify Docker Desktop & Azure CLI**:
   Ensure Docker Desktop is running locally.

3. **Log In to Azure**:
   ```powershell
   az login
   ```

4. **Execute the Native PowerShell Master Runner**:
   ```powershell
   .\infrastructure\azure\deploy_all_azure.ps1
   ```
   This script builds all images locally on your fast machine, pushes them to ACR, and orchestrates AKS rollout directly.

---

## 2. Sandbox / Student Subscription Resilience

> [!NOTE]
> In Microsoft Learn / Cloud Slice sandboxes (`rg-azuser..._mml.local-...`), student accounts are given `Contributor` access without Azure AD role assignment permissions. Azure Key Vault data-plane operations (`Microsoft.KeyVault/vaults/secrets/setSecret/action`) are restricted by tenant-level RBAC (`ForbiddenByRbac`).
> The deployment scripts automatically detect this: they attempt Key Vault in Access Policy mode, and if the sandbox restricts data-plane operations, seamlessly inject the secrets directly into Kubernetes Secret `banking-secrets` inside AKS without failing.

---

## 3. Environment Variables & Configurations Reference

| Variable Name | Default Value | Location / Component | Purpose & When to Change |
|---|---|---|---|
| `RESOURCE_GROUP` | *Auto-detected* or `rg-banking-budget-$LOCATION` | `step1_infra_provision.sh` | Set this if deploying to a pre-allocated student/lab sandbox (e.g. `export RESOURCE_GROUP="learn-rg-12345"`). |
| `AZURE_LOCATION` | `southeastasia` | `step1_infra_provision.sh` | Azure region for resources (e.g., `eastus`, `westus2`, `southeastasia`). |
| `AKS_VM_SIZE` | `Standard_B2s` | `step1_infra_provision.sh` | Memory-optimized budget VM size allowed in student sandboxes. |
| `DATADOG_API_KEY` | `00000000...` *(dummy)* | `step1_infra_provision.sh` / Key Vault | **Your Datadog API Key**. Set this (`export DATADOG_API_KEY="<YOUR_KEY>"`) to stream APM traces to your live Datadog dashboard. |
| `PARTNER_BANK_URL` | `http://transfer-orchestrator.banking.svc.cluster.local:8082/api/v1/mock-partner/transfers` | `01_transfer_orchestrator.yaml` | **Interbank Partner API**. If integrating with another group's bank API endpoint, set this to their external HTTP URL. |
| `PARTNER_BANK_API_KEY`| `AURABANK-SECURE-KEY-2026` | `01_transfer_orchestrator.yaml` | The shared API key header sent during interbank transfers. |
| `CBS_COB_SCHEDULER_ENABLED` | `false` | `08_t24_mock_cbs.yaml` | Disables internal Spring `@Scheduled` cron in AKS to ensure only the Kubernetes `CronJob` triggers the 21:00 PHT cutoff (prevents double-firing). |
| `SPRING_PROFILES_ACTIVE` | `azure` | Java Microservices | Instructs Spring Boot to load `application-azure.properties` (SQL Server dialect, cloud data sources). |
| `AZURE_STORAGE_CONNECTION_STRING` | *Auto-injected from Key Vault/Env* | `09_compliance_service.yaml` | Storage Account connection string for immutable SAR compliance vault PDF/JSON reports. |
| `JWT_SECRET` | *Pre-configured secure key* | Key Vault / `banking-secrets` | Master signing secret for HS512 authentication tokens. |

---

## 4. Post-Deployment Verification & Smoke Tests

Once deployment finishes, retrieve your public Load Balancer endpoints:
```bash
bash infrastructure/azure/step4_check_endpoints.sh
```
or
```bash
kubectl get svc -n banking
```

You will see:
- `banking-frontend`: Staff Admin Console (React) on port `80`
- `aurabank-customer-web`: Customer Retail Portal (Flutter Web) on port `80`
- `gateway-service`: Core API Gateway on ports `80` and `8080`

### Smoke Test Checklist:
1. **Customer Web Portal**:
   - Open `http://<AURABANK_CUSTOMER_WEB_IP>` in your browser.
   - Log in as customer (`testuser1` / `password123`).
   - Initiate a fund transfer.
2. **Real-time SSE Notifications**:
   - Keep the customer browser window open on the Dashboard.
   - When the transfer completes, verify that the transaction notification pops up instantly via the non-buffered Nginx SSE channel (`/api/v1/notifications/stream`).
3. **Staff Admin Console**:
   - Open `http://<BANKING_FRONTEND_IP>` in your browser.
   - Log in as Admin (`U0001` or `U3001` / `Password123!`).
   - View accounts and pending transactions.
4. **Mock MailHog Email Inbox**:
   - Port-forward MailHog to inspect transactional emails dispatched:
     ```bash
     kubectl port-forward svc/mailhog 8025:8025 -n banking
     ```
   - Open `http://localhost:8025` in your browser.
5. **Datadog APM Tracing**:
   - If `DATADOG_API_KEY` was provided, open [app.datadoghq.com/apm/traces](https://app.datadoghq.com/apm/traces).
   - Filter by `env:azure` to see distributed flame graphs spanning `gateway-service` -> `transfer-orchestrator` -> `t24-mock-cbs` -> `risk-service`.

---

## 5. Cost Conservation & Teardown (\$10 Budget Management)

### While Pausing / Between Demos (Spend Drops to ~\$0.21/day)
- In Cloud Shell: `bash infrastructure/azure/pause_budget_azure.sh`
- In PowerShell: `.\infrastructure\azure\pause_budget_azure.ps1`

### When Ready to Resume Testing / Presenting
- In Cloud Shell: `bash infrastructure/azure/resume_budget_azure.sh`
- In PowerShell: `.\infrastructure\azure\resume_budget_azure.ps1`

### Complete Teardown (Permanently Delete Everything)
When your capstone presentation is finished and you no longer need the cloud deployment:
```bash
az group delete --name "$RESOURCE_GROUP" --yes --no-wait
```
