# 2026 three-customer seed

This folder is the insert set. It is reconciled: a peer payment is one ledger row, so the sender's out and the receiver's in are the same amount on the same timestamp.

## Files

- `04_seed_analytics_2026.sql` — Oracle inserts for users, accounts, closing balances, and every 2026 transfer.
- `analytics_catalog.json` — the same ledger grouped the way `web_analytics_screen.dart` reads it.
- `risk_profiles.py` — keys to paste into `CUSTOMER_PROFILES`.
- `generate_seed_2026.py` — regenerates the three files. Do not hand-edit the outputs.

## Logins

Password for Paolo, Elena, and Marco is `password123`.

| Person | User | Account | Number | Home | Clock |
|---|---|---|---|---|---|
| Paolo Reyes | U9001 | A9001 | 1000-9000-0001 | BGC, Taguig 14.554700, 121.024400 | mornings, about 08:15 |
| Elena Cruz | U9002 | A9002 | 1000-9000-0002 | Diliman 14.676000, 121.043700 | evenings, about 19:10 |
| Marco Santos | U9003 | A9003 | 1000-9000-0003 | Cebu Business Park 10.315700, 123.885400 | afternoons, about 16:20 |

Source accounts are not demo logins. Aura Payroll `A8001` pays Paolo. Northwind Studio `A8002` pays Elena. Cebu Trade Co `A8003` pays Marco. City Utilities `A8004` receives outside spending.

## What the charts should show

Paolo is a flat salary year. Elena's client income climbs every month, and she sends 75% of what she receives. Marco is quiet early, spends through the summer, and spends again in December.

Counts in the table are real transfer counts, written as in/out.

## Year

| Customer | In | Out | Net | Opened 1 Jan | Closed 31 Dec | Lowest balance |
|---|---:|---:|---:|---:|---:|---:|
| Paolo Reyes | 398,000 | 264,000 | 134,000 | 80,000 | 214,000 | 80,000 |
| Elena Cruz | 278,000 | 208,500 | 69,500 | 50,000 | 119,500 | 49,850 |
| Marco Santos | 216,000 | 311,000 | -95,000 | 400,000 | 305,000 | 305,000 |

### Paolo Reyes

| Month | W1 in/out | W2 in/out | W3 in/out | W4 in/out | Month in | Month out | Net |
|---|---:|---:|---:|---:|---:|---:|---:|
| Jan | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Feb | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Mar | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Apr | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| May | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Jun | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Jul | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Aug | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Sep | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Oct | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Nov | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 1,500/5,800 (1/2) | 1,000/5,000 (1/2) | 32,500 | 21,500 | 11,000 |
| Dec | 28,000/4,500 (1/2) | 2,000/6,200 (1/3) | 9,500/5,800 (2/2) | 1,000/11,000 (1/4) | 40,500 | 27,500 | 13,000 |
### Elena Cruz

| Month | W1 in/out | W2 in/out | W3 in/out | W4 in/out | Month in | Month out | Net |
|---|---:|---:|---:|---:|---:|---:|---:|
| Jan | 1,200/1,350 (1/1) | 6,000/1,350 (1/2) | 3,000/4,050 (1/3) | 1,800/2,250 (1/2) | 12,000 | 9,000 | 3,000 |
| Feb | 1,400/1,575 (1/1) | 7,000/1,575 (1/2) | 3,500/4,725 (1/3) | 2,100/2,625 (1/2) | 14,000 | 10,500 | 3,500 |
| Mar | 1,600/1,800 (1/1) | 8,000/1,800 (1/2) | 4,000/5,400 (1/3) | 2,400/3,000 (1/2) | 16,000 | 12,000 | 4,000 |
| Apr | 1,800/2,025 (1/1) | 9,000/2,025 (1/2) | 4,500/6,075 (1/3) | 2,700/3,375 (1/2) | 18,000 | 13,500 | 4,500 |
| May | 2,000/2,250 (1/1) | 10,000/2,250 (1/2) | 5,000/6,750 (1/4) | 3,000/3,750 (1/2) | 20,000 | 15,000 | 5,000 |
| Jun | 2,200/2,475 (1/1) | 11,000/2,475 (1/2) | 5,500/7,425 (1/4) | 3,300/4,125 (1/2) | 22,000 | 16,500 | 5,500 |
| Jul | 2,400/2,700 (1/2) | 12,000/2,700 (1/2) | 6,000/8,100 (1/4) | 3,600/4,500 (1/2) | 24,000 | 18,000 | 6,000 |
| Aug | 2,600/2,925 (1/2) | 13,000/2,925 (1/2) | 6,500/8,775 (1/4) | 3,900/4,875 (1/2) | 26,000 | 19,500 | 6,500 |
| Sep | 2,800/3,150 (1/2) | 14,000/3,150 (1/2) | 7,000/9,450 (1/5) | 4,200/5,250 (1/2) | 28,000 | 21,000 | 7,000 |
| Oct | 3,000/3,375 (1/2) | 15,000/3,375 (1/2) | 7,500/10,125 (1/5) | 4,500/5,625 (1/2) | 30,000 | 22,500 | 7,500 |
| Nov | 3,200/3,600 (1/2) | 16,000/3,600 (1/2) | 8,000/10,800 (1/5) | 4,800/6,000 (1/2) | 32,000 | 24,000 | 8,000 |
| Dec | 3,600/4,050 (1/2) | 18,000/4,050 (1/2) | 9,000/12,150 (1/6) | 5,400/6,750 (1/2) | 36,000 | 27,000 | 9,000 |
### Marco Santos

| Month | W1 in/out | W2 in/out | W3 in/out | W4 in/out | Month in | Month out | Net |
|---|---:|---:|---:|---:|---:|---:|---:|
| Jan | 4,800/1,500 (1/1) | 2,400/2,000 (1/1) | 2,400/2,500 (1/1) | 2,400/4,000 (1/2) | 12,000 | 10,000 | 2,000 |
| Feb | 5,600/1,800 (1/1) | 2,800/2,400 (1/2) | 2,800/3,000 (1/1) | 2,800/4,800 (1/2) | 14,000 | 12,000 | 2,000 |
| Mar | 5,600/2,400 (1/1) | 2,800/3,200 (1/2) | 2,800/4,000 (1/1) | 2,800/6,400 (1/2) | 14,000 | 16,000 | -2,000 |
| Apr | 6,400/3,000 (1/1) | 3,200/4,000 (1/2) | 3,200/5,000 (1/1) | 3,200/8,000 (1/2) | 16,000 | 20,000 | -4,000 |
| May | 7,200/3,600 (1/1) | 3,600/4,800 (1/2) | 3,600/6,000 (1/2) | 3,600/9,600 (1/2) | 18,000 | 24,000 | -6,000 |
| Jun | 7,200/5,250 (1/2) | 3,600/7,000 (1/2) | 3,600/8,750 (1/2) | 3,600/14,000 (1/4) | 18,000 | 35,000 | -17,000 |
| Jul | 8,000/6,300 (1/2) | 4,000/8,400 (1/3) | 4,000/10,500 (1/3) | 4,000/16,800 (1/4) | 20,000 | 42,000 | -22,000 |
| Aug | 8,800/7,200 (1/2) | 4,400/9,600 (1/3) | 4,400/12,000 (1/3) | 4,400/19,200 (1/4) | 22,000 | 48,000 | -26,000 |
| Sep | 6,400/2,700 (1/1) | 3,200/3,600 (1/2) | 3,200/4,500 (1/1) | 3,200/7,200 (1/2) | 16,000 | 18,000 | -2,000 |
| Oct | 7,200/2,400 (1/1) | 3,600/3,200 (1/2) | 3,600/4,000 (1/1) | 3,600/6,400 (1/2) | 18,000 | 16,000 | 2,000 |
| Nov | 6,400/3,300 (1/1) | 3,200/4,400 (1/2) | 3,200/5,500 (1/2) | 3,200/8,800 (1/2) | 16,000 | 22,000 | -6,000 |
| Dec | 12,800/7,200 (1/2) | 6,400/9,600 (1/3) | 6,400/12,000 (1/3) | 6,400/19,200 (1/4) | 32,000 | 48,000 | -16,000 |

## How the weeks stay consistent

- Week 1 is days 1–7, week 2 is 8–14, week 3 is 15–21, week 4 is the 22nd through month end. February 2026 ends on the 28th.
- Income lands on the first day of the week. Spending lands three days later, still inside that week.
- Every month, Marco sends Paolo 2,000 in week 2. That is Paolo's entire week 2 income.
- Every month, Elena sends Paolo 1,500 in week 3. December adds an 8,000 payroll bonus, so Paolo's week 3 income is 9,500.
- Every month, Paolo sends Elena her entire week 4 income. In October that is 4,500, so her October week 4 received is 4,500 and his October week 4 sent is 5,000 (4,500 to Elena and 500 to City Utilities).
- External money fills only the gap. It is never negative.
- Every outgoing amount through 11 Oct is under 2x that customer's average, so a later risk score is about place and elapsed time.
- GPS and IP on a row belong to the sender and never change. Same place means velocity near 0.

## Risk anchor

Today for the risk profiles is 12 Oct 2026. The newest outgoing transfer used as `last_transaction` is on 11 Oct 2026. Later October and all of November and December remain in the ledger for the year chart.

- Paolo Reyes `U9001` / `A9001` / `1000-9000-0001`: age 423 days, average outgoing PHP 2,374.42, largest outgoing through 11 Oct PHP 4,200 (1.77x average), balance on 11 Oct PHP 198,300, last transfer `TX-2026-0326` PHP 2,000 at 2026-10-11T09:05:00+08:00 from Bonifacio Global City, Taguig.
- Elena Cruz `U9002` / `A9002` / `1000-9000-0002`: age 423 days, average outgoing PHP 1,667.65, largest outgoing through 11 Oct PHP 2,625 (1.57x average), balance on 11 Oct PHP 106,250, last transfer `TX-2026-0330` PHP 1,687 at 2026-10-11T19:35:00+08:00 from Diliman, Quezon City.
- Marco Santos `U9003` / `A9003` / `1000-9000-0003`: age 423 days, average outgoing PHP 3,158.90, largest outgoing through 11 Oct PHP 5,000 (1.58x average), balance on 11 Oct PHP 330,200, last transfer `TX-2026-0328` PHP 1,200 at 2026-10-11T16:45:00+08:00 from Cebu Business Park, Cebu City.

`server.py` reads only this seeded `last_transaction`. `main.py` uses `previous_latitude`, `previous_longitude`, and `previous_timestamp` from the request when those fields are present. A normal test sends the same home coordinates as the last transfer, with a new clock time.

## What the other IDE should change

1. Run `04_seed_analytics_2026.sql` in schema `fse_user` after `infrastructure/oracle/init.sql`. These ids do not overlap Juan `U1001` / `A2001`.
2. Paste `risk_profiles.py` into `backend/risk-service/app/seed_data.py`. Register each profile under the user id, the account id, and the account number. The service will not see the SQL by itself.
3. Point `aurabank_web/lib/screens/web/analytics/web_analytics_screen.dart` at `analytics_catalog.json` for the signed-in user. The screen still ships one hardcoded `_monthsCatalog`, so the database will not change the chart until that catalog is replaced. Month index 9 is October. Week index 3 is week 4. `net` is received minus sent.
4. Do not insert `available_balance`. It is a generated column. `from_account_id` and `to_account_id` both reference `accounts`. Amount is greater than 0. Status is `POSTED`. Type is `TRANSFER`. `transaction_type` is `INTRA_BANK`.
5. `balance_master.balance_amount` in the SQL is the 31 Dec 2026 close, after every row in this file.

Transfer count: 429.
