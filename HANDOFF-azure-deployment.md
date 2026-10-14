# Handoff: AuraBank on Azure (AKS) - state as of 2026-10-14

Paste this file into a new Claude session. It says what exists, what was verified, and what is still open.

Repo: https://github.com/romzelmadidev/group3_fse_capstone (branch `main`, last pushed commit `9e12b6a`).
Workspace on the user's PC: `C:\Users\JLB83807\The Vault\workspaces\FSE-Capstone` (Windows, PowerShell).
Deploy target: Azure Cloud Shell (Bash), one subscription shared by teammates, one Resource Group each.

## How the user works
- Terse, wants speed. Says "push to main" explicitly each time; do NOT commit or push without that instruction.
- Workspace rules are in `AGENTS.md` (surgical changes, verify before claiming done). Do not claim success without evidence.
- `.sh` files must stay LF (`.gitattributes`). Syntax check on this PC: `& "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe" -n <file>`.
- Local Docker engine was available earlier (used for a throwaway SQL Server 2022 container) but was stopped later. Do not assume it runs.
- Untracked files that are NOT part of this work (leave alone): `.archify/`, `breakscale*`, `scripts/breakscale_simulate.js`, `scripts/reset-environment.ps1`, `scripts/serve_simulator.js`, `backend/.mvn/maven.config`, `docs/presentation/`, `aurabank_breakscale_simulator.html`.

## The one command
```bash
# Azure Cloud Shell (Bash)
git clone https://github.com/romzelmadidev/group3_fse_capstone aurabank && cd aurabank
bash infrastructure/azure/run_azure.sh            # deploy or repair (idempotent)
bash infrastructure/azure/run_azure.sh status     # pods + URLs
# env knobs: RESOURCE_GROUP, NODE_COUNT(2), MAX_NODES(4), AKS_VM_SIZE, SQL_TIER(S1), REBUILD=all|img,img,
#            RESET_DB=true (drops+recreates the SQL DB; only for a half-migrated/empty DB), DATADOG_API_KEY, SQL_LOCATION
```
`infrastructure/azure/run_azure.sh` does: preflight -> RG/SQL/Storage/ACR -> AKS (2 nodes, auto-adds a node if pods are Pending) -> secrets -> builds only changed images in ACR (tags `src-<git tree hash>`) -> applies `infrastructure/k8s/*.yaml` -> waits for every deployment Ready (prints errors once a minute) -> smoke tests -> prints URLs. Uses a PID lock `/tmp/aurabank-run.pid`. If it says another run is active: `pgrep -af run_azure.sh`; if none, `rm -f /tmp/aurabank-run.lock /tmp/aurabank-run.pid`.

## Last known good run (user's Cloud Shell, 2026-10-14 ~00:30)
All deployments Ready; smoke tests PASS (gateway 200, login path 401 for bad creds, staff console 200, customer web 200).
- RG `rg-azuser8389_mml.local-WP8E9`, AKS `aks-aurabank` (2 x Standard_D2s_v3; B2s is not allowed in centralindia), ACR `acraurabank78491e`, SQL server `sql-aurabank-3c322a`.
- Staff console http://135.235.177.218 , customer web http://20.204.232.161 , gateway http://20.244.72.151:8080 (public IPs change if services are recreated).
- Mobile app: Login screen -> server icon -> Azure Cloud -> `http://<gateway-ip>:8080` -> Save & Close.

## What was fixed in this effort (all on `main`)
1. Mobile app: generic failure text on the transfer receipt; reusable "Backend & Cloud Routing" sheet reachable from the login screen; URL persisted in SharedPreferences; bare IPs get `http://`; in cloud mode auth goes only to the gateway (no localhost/LAN retries). Files: `aurabank_core/lib/widgets/backend_routing_sheet.dart`, `bank_service.dart`, `auth_api_service.dart`, `aurabank_app/.../receipt_screen.dart`, `settings_screen.dart`, `login_screen.dart`.
2. Pending pods: root cause was a single 4 GiB node. Now 2 nodes + self-healing scale-up; startup probes (10 min window), CPU limits raised; Kafka heap capped (was OOMKilled); `t24-mock-cbs` 2 CPU / 1 Gi / 640m heap; risk-service memory 768Mi; Datadog agent skipped unless a real `DATADOG_API_KEY` is set.
3. Image builds: Java Dockerfiles no longer fail when the gitignored `backend/datadog/dd-java-agent.jar` is absent (script also downloads it); `risk-service` uses new `backend/risk-service/Dockerfile.azure` (repo-root context, bakes in `hybrid_bench/`, which compose only bind-mounts).
4. Azure SQL schema is created by `t24-mock-cbs` Flyway from `backend/t24-mock-cbs/src/main/resources/db/migration/sqlserver/`:
   - `V1__azure_sql_master_schema.sql` was a stale copy; fixed (participant_banks seeded after accounts, view column, plus columns the JPA entities need: users KYC/geo/last_login_at, accounts.currency, balance_master.balance_id, transactions, outbox kafka_topic, notifications shape, dbo synonyms). Verified on SQL Server 2022.
   - `V2__team_demo_accounts.sql`: customers wax/hans/jm/zel/jessy/maye/angel `@bank.com` / `password123`, accounts `1000-8801-0001..7`, PHP 1,000,000 each (bcrypt hash verified).
   - `V3__staff_console_accounts.sql`: staff `<name>.staff@bank.com` / `Staff@Aura2026` (wax/hans/zel/maye ADMIN; jm/jessy/angel TELLER), Crisostomo, Diana moved to staff password. Hash verified with Spring BCrypt. **Not run on SQL Server and not yet confirmed working on the user's cluster.**
   - Never edit an already-applied migration; add V4+.
   - `infrastructure/azure/01_azure_sql_master_schema.sql` is kept identical to V1 (no longer used by the script).
5. Staff console (`frontend/`): `crypto.randomUUID` is undefined on plain HTTP; added `frontend/src/lib/uuid.js` fallback used by `api.js` and `ui.jsx`.
6. `infrastructure/k8s/02_account_service.yaml`: added `AZURE_STORAGE_CONNECTION_STRING` (from secret), `AZURE_STORAGE_CONTAINER_NAME=kyc-vault`, `APP_SERVICES_RISK_SERVICE_URL`, `APP_SERVICES_CBS_URL`, `NOTIFICATION_SERVICE_URL`. Before this, account-service fell back to Azurite/localhost and KYC upload failed.

## OPEN TASK 1 - expose MailHog publicly (user approved: "make it public, this is just testing")
Why: OTP emails go to the in-cluster MailHog (`svc/mailhog`, ClusterIP, SMTP 1025, UI/API 8025). Cloud Shell `kubectl port-forward` is not reachable from the user's laptop (Web preview on port 8025 works as a workaround).
Do:
- Add a public Service for MailHog UI, e.g. in `infrastructure/k8s/00_in_cluster_dependencies.yaml` or a new `infrastructure/k8s/11_mailhog_public.yaml`: `type: LoadBalancer`, port 8025 -> targetPort 8025, selector `app: mailhog`. Keep SMTP 1025 internal.
- In `run_azure.sh`: wait for its IP (`svc_ip`), print `Mail inbox: http://<ip>:8025` in `print_summary`, add a smoke check (`/api/v2/messages` returns 200). Make it opt-out (e.g. `EXPOSE_MAILHOG=false`) so it is on for testing.
- Security: MailHog has no auth, so anyone with the IP can read every OTP. Say so in the summary and README; suggest `loadBalancerSourceRanges` or turning it off before any real use.
- Note the public-IP quota (gateway, staff console, customer web, + MailHog + AKS outbound).

## OPEN TASK 2 - KYC: "Unable to reach identity verification service. Please try again."
Screen: mobile Identity Verification -> "Verification Needs Attention". Message source: `aurabank_app/lib/services/kyc_service.dart:314`.
Progress: the earlier error was "Failed to upload document front" (blob upload). After the account-service env fix the app now fails at a later step, which suggests the upload may now work (unconfirmed).
Call chain: app -> gateway -> account-service `KycController`/`KycService` -> `RiskServiceKycClient` POST `${app.services.risk-service.url}/api/v1/kyc/evaluate` (timeouts: connect 5 s, read 15 s) -> risk-service (`backend/risk-service/app/server.py:456` routes `/api/v1/kyc/evaluate`; `main.py:1073` also defines it). Container entrypoint is `python -m app.server`.
Diagnose in this order (Cloud Shell):
```bash
kubectl get pods -n banking
kubectl logs deploy/risk-service -n banking --tail=80
kubectl logs deploy/account-service -n banking --tail=120 | grep -iE "kyc|risk|blob|storage|Failed to evaluate"
kubectl exec deploy/account-service -n banking -- printenv | grep -E "RISK|CBS|STORAGE|NOTIFICATION"
# call risk-service directly from inside the cluster to see latency/error:
kubectl run curl --rm -it --restart=Never --image=curlimages/curl -n banking -- curl -s -m 30 -o /dev/null -w "%{http_code} %{time_total}\n" -X POST http://risk-service.banking.svc.cluster.local:8084/api/v1/kyc/evaluate -H 'Content-Type: application/json' -d '{}'
```
Hypotheses (none confirmed): (a) account-service env `APP_SERVICES_RISK_SERVICE_URL` not picked up by `@Value("${app.services.risk-service.url:...}")` (check printenv above and the log line "Failed to evaluate KYC via Laya Risk Service"); (b) risk-service KYC evaluation is slow (OCR/face models, CPU limit) and exceeds the 15 s read timeout; (c) risk-service errors on `/api/v1/kyc/evaluate` because the Azure image lacks a model/data file that local docker-compose mounts (compose also mounts `backend/risk-service/data` and `hybrid_bench`); (d) the KYC request payload references blob URLs the risk-service cannot read (it may need a SAS read URL or the storage connection string - check `KycEvaluationClientRequest` and risk-service `kyc` code, and whether risk-service needs `AZURE_STORAGE_*` env, which `04_risk_service.yaml` does not set).
If a fix needs image changes, remember images are rebuilt automatically on the next `run_azure.sh` when source trees change.

## OPEN TASK 3 - verify (not yet confirmed by the user)
- Staff console login with `jm.staff@bank.com` / `Staff@Aura2026` after V3 is applied (do a hard refresh; check `kubectl logs deploy/t24-mock-cbs -n banking | grep -i flyway` shows V3 applied).
- Customer app login `jm@bank.com` / `password123` works (user reported yes).
- Registration OTP email arrives in MailHog.

## Gotchas learned
- Teammates share one subscription: SQL/ACR/Storage names are global, so the name suffix is derived from subscription + RG, with retry on `NameAlreadyExists`.
- `kubectl apply` race ("AlreadyExists") happens when two script runs overlap; the script now retries and locks.
- `RESET_DB=true` deletes the Azure SQL DB; only use it when nothing real is in it.
- Demo credentials (`password123`, `Staff@Aura2026`) are seeded into a publicly reachable cloud DB. Fine for testing, remove before real use.
- Cost: S1 SQL ~ $1/day, 2 AKS nodes ~ $2-3/day. `bash infrastructure/azure/pause_budget_azure.sh` stops the cluster when idle.
