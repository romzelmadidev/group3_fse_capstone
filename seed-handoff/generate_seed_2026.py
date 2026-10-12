"""Build a reconciled 2026 ledger for three Aura customers.

Outputs SQL, risk profiles, and the analytics catalog another IDE can apply.
Peer transfers are inserted once. External payers fill only a positive gap.
"""

from __future__ import annotations

import json
from calendar import monthrange
from collections import defaultdict
from datetime import datetime
from pathlib import Path

YEAR = 2026
AS_OF = datetime(2026, 10, 11, 23, 59)
OUT = Path(__file__).resolve().parent

MONTHS = [
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
]
SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]

PASSWORD_HASH = "$2a$10$Yc8Pb5dWtINUdZYHEQ72fOX0g.GqUn1B3BkspBIiuTkmN.1Jwf1PC"
PIN_HASH = "$2a$12$k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8k4L9m1Wq2P8"

# Sender device location is stored on the transfer row.
PEOPLE = {
    "A9001": {
        "user_id": "U9001",
        "account_number": "1000-9000-0001",
        "first": "Paolo",
        "middle": "Santos",
        "last": "Reyes",
        "email": "paolo.reyes@email.com",
        "phone": "09170009001",
        "dob": "1994-03-18",
        "gov": "PSA-9001-0318",
        "name": "Paolo Reyes",
        "initial": "PR",
        "color": "0xFF1C6E5A",
        "lat": 14.554700,
        "lon": 121.024400,
        "loc": "Bonifacio Global City, Taguig",
        "ip": "112.198.45.10",
        "opening": 80000,
        "slots": [(8, 15), (8, 40), (9, 5), (9, 30), (9, 55)],
        "limit": 100000,
        "created": "2025-08-15 09:00:00",
    },
    "A9002": {
        "user_id": "U9002",
        "account_number": "1000-9000-0002",
        "first": "Elena",
        "middle": "Marie",
        "last": "Cruz",
        "email": "elena.cruz@email.com",
        "phone": "09170009002",
        "dob": "1996-07-09",
        "gov": "PSA-9002-0709",
        "name": "Elena Cruz",
        "initial": "EC",
        "color": "0xFF6E7BD9",
        "lat": 14.676000,
        "lon": 121.043700,
        "loc": "Diliman, Quezon City",
        "ip": "112.198.77.22",
        "opening": 50000,
        "slots": [(19, 10), (19, 35), (20, 0), (20, 25), (20, 50)],
        "limit": 100000,
        "created": "2025-08-15 09:10:00",
    },
    "A9003": {
        "user_id": "U9003",
        "account_number": "1000-9000-0003",
        "first": "Marco",
        "middle": "Antonio",
        "last": "Santos",
        "email": "marco.santos@email.com",
        "phone": "09170009003",
        "dob": "1989-11-02",
        "gov": "PSA-9003-1102",
        "name": "Marco Santos",
        "initial": "MS",
        "color": "0xFFD9822B",
        "lat": 10.315700,
        "lon": 123.885400,
        "loc": "Cebu Business Park, Cebu City",
        "ip": "112.203.18.44",
        "opening": 400000,
        "slots": [(16, 20), (16, 45), (17, 10), (17, 35), (18, 0)],
        "limit": 500000,
        "created": "2025-08-15 09:20:00",
    },
    "A8001": {
        "user_id": "U8001",
        "account_number": "1000-8000-0001",
        "first": "Aura",
        "middle": "Bank",
        "last": "Payroll",
        "email": "payroll.ops@aurabank.com",
        "phone": "09170008001",
        "dob": "2010-01-01",
        "gov": "SEC-8001-PAY",
        "name": "Aura Payroll",
        "initial": "AP",
        "color": "0xFF2F78A8",
        "lat": 14.554700,
        "lon": 121.024400,
        "loc": "Bonifacio Global City, Taguig",
        "ip": "112.198.10.1",
        "opening": 1000000,
        "slots": [(8, 0), (8, 5), (8, 10)],
        "limit": 1000000,
        "created": "2025-01-06 08:00:00",
        "source": True,
    },
    "A8002": {
        "user_id": "U8002",
        "account_number": "1000-8000-0002",
        "first": "Northwind",
        "middle": "Design",
        "last": "Studio",
        "email": "billing@northwind.example",
        "phone": "09170008002",
        "dob": "2012-04-01",
        "gov": "SEC-8002-NW",
        "name": "Northwind Studio",
        "initial": "NS",
        "color": "0xFF2FA37E",
        "lat": 14.586900,
        "lon": 121.061400,
        "loc": "Ortigas Center, Pasig",
        "ip": "122.54.20.15",
        "opening": 1000000,
        "slots": [(10, 0), (10, 20), (10, 40)],
        "limit": 1000000,
        "created": "2025-01-06 08:05:00",
        "source": True,
    },
    "A8003": {
        "user_id": "U8003",
        "account_number": "1000-8000-0003",
        "first": "Cebu",
        "middle": "Trade",
        "last": "Company",
        "email": "treasury@cebutrade.example",
        "phone": "09170008003",
        "dob": "2008-06-01",
        "gov": "SEC-8003-CT",
        "name": "Cebu Trade Co",
        "initial": "CT",
        "color": "0xFF3FA7C9",
        "lat": 10.315700,
        "lon": 123.885400,
        "loc": "Cebu Business Park, Cebu City",
        "ip": "112.203.18.2",
        "opening": 1000000,
        "slots": [(11, 0), (11, 20), (11, 40)],
        "limit": 1000000,
        "created": "2025-01-06 08:10:00",
        "source": True,
    },
    "A8004": {
        "user_id": "U8004",
        "account_number": "1000-8000-0004",
        "first": "City",
        "middle": "Bills",
        "last": "Utilities",
        "email": "collections@cityutilities.example",
        "phone": "09170008004",
        "dob": "2005-09-01",
        "gov": "SEC-8004-CU",
        "name": "City Utilities",
        "initial": "CU",
        "color": "0xFFD9705A",
        "lat": 14.599500,
        "lon": 120.984200,
        "loc": "Manila, Philippines",
        "ip": "112.198.90.8",
        "opening": 0,
        "slots": [(12, 0)],
        "limit": 1000000,
        "created": "2025-01-06 08:15:00",
        "source": True,
    },
}

PAOLO, ELENA, MARCO = "A9001", "A9002", "A9003"
PAY, NORTH, CEBU, BILLS = "A8001", "A8002", "A8003", "A8004"
CUSTOMERS = [PAOLO, ELENA, MARCO]

ELENA_IN = [12000 + i * 2000 for i in range(11)] + [36000]
MARCO_IN = [12000, 14000, 14000, 16000, 18000, 18000, 20000, 22000, 16000, 18000, 16000, 32000]
MARCO_OUT = [10000, 12000, 16000, 20000, 24000, 35000, 42000, 48000, 18000, 16000, 22000, 48000]


def parts(total: int, weights: list[int]) -> list[int]:
    raw = [total * w // 100 for w in weights[:-1]]
    raw.append(total - sum(raw))
    if any(v < 0 for v in raw) or sum(raw) != total:
        raise SystemExit(f"bad split {total} {weights} -> {raw}")
    return raw


def split_n(amount: int, n: int) -> list[int]:
    if amount <= 0:
        return []
    if n < 1:
        raise SystemExit("n < 1")
    base, rem = divmod(amount, n)
    return [base + (1 if i < rem else 0) for i in range(n)]


def chunks(amount: int, cap: int) -> list[int]:
    if amount <= 0:
        return []
    n = (amount + cap - 1) // cap
    return split_n(amount, n)


def paolo_targets(month: int) -> tuple[list[int], list[int]]:
    incoming = [28000, 2000, 1500, 1000]
    outgoing = [4500, 6200, 5800, 5000]
    if month == 12:
        incoming[2] = 9500
        outgoing[3] = 11000
    return incoming, outgoing


def week_bounds(month: int, week: int) -> tuple[int, int]:
    last = monthrange(YEAR, month)[1]
    return [(1, 7), (8, 14), (15, 21), (22, last)][week]


def in_day(month: int, week: int) -> int:
    return week_bounds(month, week)[0]


def out_day(month: int, week: int) -> int:
    start, end = week_bounds(month, week)
    return min(start + 3, end)


def week_index(month: int, day: int) -> int:
    if day <= 7:
        return 0
    if day <= 14:
        return 1
    if day <= 21:
        return 2
    if day <= monthrange(YEAR, month)[1]:
        return 3
    raise SystemExit(f"bad day {YEAR}-{month}-{day}")


def fmt_oracle(ts: datetime) -> str:
    return f"TIMESTAMP '{ts.strftime('%Y-%m-%d %H:%M:%S')} +08:00'"


def fmt_iso(ts: datetime) -> str:
    return ts.strftime("%Y-%m-%dT%H:%M:%S+08:00")


def fmt_ui_time(ts: datetime) -> str:
    hour = ts.hour % 12 or 12
    suffix = "am" if ts.hour < 12 else "pm"
    return f"{ts.strftime('%b')} {ts.day:02d}, {hour}:{ts.minute:02d} {suffix}"


def fmt_subtitle(month: int, week: int) -> str:
    start, end = week_bounds(month, week)
    return f"{SHORT[month - 1]} {start:02d} - {SHORT[month - 1]} {end:02d}, {YEAR}"


def build() -> tuple[list[dict], dict]:
    cursor: dict[tuple, int] = defaultdict(int)
    events: list[dict] = []
    expected: dict[tuple, tuple[int, int]] = {}

    def stamp(sender: str, month: int, day: int) -> datetime:
        key = (sender, month, day)
        index = cursor[key]
        cursor[key] = index + 1
        slots = PEOPLE[sender]["slots"]
        hour, minute = slots[index % len(slots)]
        minute += 5 * (index // len(slots))
        hour += minute // 60
        minute %= 60
        return datetime(YEAR, month, day, hour, minute)

    def add(sender: str, receiver: str, amount: int, month: int, day: int, memo: str) -> None:
        if amount <= 0:
            raise SystemExit(f"non-positive {sender}->{receiver} {amount} {memo}")
        if week_index(month, day) is None:
            raise SystemExit("week")
        events.append({
            "from": sender,
            "to": receiver,
            "amount": amount,
            "month": month,
            "day": day,
            "week": week_index(month, day),
            "ts": stamp(sender, month, day),
            "memo": memo,
        })

    for month in range(1, 13):
        elena_in = parts(ELENA_IN[month - 1], [10, 50, 25, 15])
        elena_out = parts(ELENA_IN[month - 1] * 3 // 4, [15, 15, 45, 25])
        marco_in = parts(MARCO_IN[month - 1], [40, 20, 20, 20])
        marco_out = parts(MARCO_OUT[month - 1], [15, 20, 25, 40])
        paolo_in, paolo_out = paolo_targets(month)
        if elena_in[3] > paolo_out[3]:
            raise SystemExit(f"Paolo cannot fund Elena week 4 in month {month}")
        if marco_out[1] < 2000:
            raise SystemExit(f"Marco week 2 too small in month {month}")
        if elena_out[2] < 1500:
            raise SystemExit(f"Elena week 3 too small in month {month}")

        for week in range(4):
            expected[(PAOLO, month, week)] = (paolo_in[week], paolo_out[week])
            expected[(ELENA, month, week)] = (elena_in[week], elena_out[week])
            expected[(MARCO, month, week)] = (marco_in[week], marco_out[week])
            credit_day = in_day(month, week)
            debit_day = out_day(month, week)

            if week == 0:
                add(PAY, PAOLO, 28000, month, credit_day, "Monthly salary")
            if week == 2 and month == 12:
                add(PAY, PAOLO, 8000, month, credit_day, "Year-end bonus")
            if week == 3:
                add(PAY, PAOLO, 1000, month, credit_day, "Payroll cashback")
            if week < 3:
                add(NORTH, ELENA, elena_in[week], month, credit_day, "Client payment")
            add(CEBU, MARCO, marco_in[week], month, credit_day, "Trade proceeds")

            if week == 0:
                add(PAOLO, BILLS, 2500, month, debit_day, "Rent share")
                add(PAOLO, BILLS, 2000, month, debit_day, "Groceries")
            elif week == 1:
                add(PAOLO, BILLS, 2200, month, debit_day, "Utilities")
                add(PAOLO, BILLS, 2000, month, debit_day, "Transport")
                add(PAOLO, BILLS, 2000, month, debit_day, "Groceries")
            elif week == 2:
                add(PAOLO, BILLS, 3000, month, debit_day, "Family support")
                add(PAOLO, BILLS, 2800, month, debit_day, "Groceries")
            else:
                add(PAOLO, ELENA, elena_in[3], month, debit_day, "Transfer to Elena Cruz")
                rest = paolo_out[3] - elena_in[3]
                pieces = [2000, 1800, 1800] if month == 12 else [rest]
                if sum(pieces) != rest:
                    raise SystemExit(f"December Paolo remainder {rest} != {pieces}")
                for amount in pieces:
                    add(PAOLO, BILLS, amount, month, debit_day, "Holiday expenses" if month == 12 else "Card payment")

            if week == 0:
                for amount in chunks(elena_out[0], 2500):
                    add(ELENA, BILLS, amount, month, debit_day, "Studio expenses")
            elif week == 1:
                for amount in split_n(elena_out[1], 2):
                    add(ELENA, BILLS, amount, month, debit_day, "Supplies")
            elif week == 2:
                add(ELENA, PAOLO, 1500, month, debit_day, "Transfer to Paolo Reyes")
                for amount in chunks(elena_out[2] - 1500, 2500):
                    add(ELENA, BILLS, amount, month, debit_day, "Contractor payout")
            else:
                for amount in split_n(elena_out[3], 2):
                    add(ELENA, BILLS, amount, month, debit_day, "Software and tools")

            if week == 0:
                for amount in chunks(marco_out[0], 5000):
                    add(MARCO, BILLS, amount, month, debit_day, "Shop inventory")
            elif week == 1:
                add(MARCO, PAOLO, 2000, month, debit_day, "Transfer to Paolo Reyes")
                for amount in chunks(marco_out[1] - 2000, 5000):
                    add(MARCO, BILLS, amount, month, debit_day, "Shop inventory")
            elif week == 2:
                for amount in chunks(marco_out[2], 5000):
                    add(MARCO, BILLS, amount, month, debit_day, "Supplier payment")
            else:
                for piece in split_n(marco_out[3], 2):
                    for amount in chunks(piece, 5000):
                        add(MARCO, BILLS, amount, month, debit_day, "Supplier payment")

    events.sort(key=lambda row: (row["ts"], row["from"], row["to"], row["amount"], row["memo"]))
    seen = set()
    for row in events:
        key = (row["from"], row["ts"])
        if key in seen:
            raise SystemExit(f"duplicate timestamp {key}")
        seen.add(key)

    balances = {acct: PEOPLE[acct]["opening"] for acct in PEOPLE}
    lowest = dict(balances)
    for index, row in enumerate(events, start=1):
        sender = row["from"]
        receiver = row["to"]
        amount = row["amount"]
        before = balances[sender]
        if before < amount:
            raise SystemExit(
                f"{sender} would go negative on {row['ts']} {row['memo']}: "
                f"balance {before} amount {amount}"
            )
        balances[sender] = before - amount
        balances[receiver] += amount
        lowest[sender] = min(lowest[sender], balances[sender])
        lowest[receiver] = min(lowest[receiver], balances[receiver])
        row["id"] = f"TX-2026-{index:04d}"
        row["before"] = before
        row["after"] = balances[sender]

    actual = defaultdict(lambda: [0, 0, 0, 0])
    for row in events:
        for acct, side in ((row["to"], 0), (row["from"], 1)):
            if acct not in CUSTOMERS:
                continue
            bucket = actual[(acct, row["month"], row["week"])]
            bucket[side] += row["amount"]
            bucket[side + 2] += 1

    for key, (want_in, want_out) in expected.items():
        got_in, got_out, _, _ = actual[key]
        if (got_in, got_out) != (want_in, want_out):
            raise SystemExit(f"mismatch {key}: want {(want_in, want_out)} got {(got_in, got_out)}")

    conserved = sum(balances.values()) - sum(PEOPLE[a]["opening"] for a in PEOPLE)
    if conserved != 0:
        raise SystemExit(f"money not conserved: {conserved}")

    return events, {"balances": balances, "lowest": lowest, "actual": actual}


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def write_sql(events: list[dict], balances: dict) -> None:
    lines = [
        "-- Aura 2026 analytics seed. Three customers plus four source accounts.",
        "-- Run in fse_user after infrastructure/oracle/init.sql.",
        "-- Password for every new login is password123.",
        "-- available_balance is generated. Do not insert it.",
        "-- Each transfer exists once. Sender latitude/longitude/ip are the sender device.",
        "-- Week bounds match the analytics screen: 1-7, 8-14, 15-21, 22-end.",
        "-- Risk rows below are ledger history. risk_score is left null for the engine.",
        "WHENEVER SQLERROR EXIT FAILURE;",
        "",
    ]
    order = [PAOLO, ELENA, MARCO, PAY, NORTH, CEBU, BILLS]
    for acct in order:
        person = PEOPLE[acct]
        lines.append(
            "INSERT INTO users (user_id, first_name, middle_name, last_name, email, phone_number, "
            "dob, government_id, role, password_hash, pin_hash, max_concurrent_sessions, "
            "failed_login_attempts, status, kyc_status, last_known_latitude, last_known_longitude, "
            "last_known_location_name, last_known_ip, last_geo_updated_at, created_at, updated_at) VALUES ("
            f"{sql_quote(person['user_id'])}, {sql_quote(person['first'])}, {sql_quote(person['middle'])}, "
            f"{sql_quote(person['last'])}, {sql_quote(person['email'])}, {sql_quote(person['phone'])}, "
            f"TO_DATE({sql_quote(person['dob'])}, 'YYYY-MM-DD'), {sql_quote(person['gov'])}, 'CUSTOMER', "
            f"{sql_quote(PASSWORD_HASH)}, {sql_quote(PIN_HASH)}, 3, 0, 'ACTIVE', 'VERIFIED', "
            f"{person['lat']:.6f}, {person['lon']:.6f}, {sql_quote(person['loc'])}, {sql_quote(person['ip'])}, "
            f"TIMESTAMP '{person['created']} +08:00', TIMESTAMP '{person['created']} +08:00', "
            f"TIMESTAMP '{person['created']} +08:00');"
        )
    lines.append("")
    for acct in order:
        person = PEOPLE[acct]
        lines.append(
            "INSERT INTO accounts (account_id, user_id, account_number, account_type, currency, status, created_at, updated_at) VALUES ("
            f"{sql_quote(acct)}, {sql_quote(person['user_id'])}, {sql_quote(person['account_number'])}, "
            f"'SAVINGS', 'PHP', 'ACTIVE', TIMESTAMP '{person['created']} +08:00', "
            f"TIMESTAMP '{person['created']} +08:00');"
        )
    lines.append("")
    for acct in order:
        lines.append(
            "INSERT INTO balance_master (balance_id, account_id, balance_amount, hold_amount, created_at, updated_at) VALUES ("
            f"{sql_quote('bal-' + acct)}, {sql_quote(acct)}, {balances[acct]:.4f}, 0.0000, "
            "TIMESTAMP '2026-12-31 23:59:00 +08:00', TIMESTAMP '2026-12-31 23:59:00 +08:00');"
        )
    lines.append("")
    lines.append("-- Opening balances before 2026-01-01, for audit only. balance_master above is the 2026-12-31 close.")
    for acct in order:
        lines.append(f"-- {acct} opened at {PEOPLE[acct]['opening']:.4f} and closes at {balances[acct]:.4f}")
    lines.append("")
    for row in events:
        sender = PEOPLE[row["from"]]
        lines.append(
            "INSERT INTO transactions (transaction_id, from_account_id, to_account_id, type, transaction_type, "
            "currency, amount, before_balance, after_balance, status, requires_2fa_otp, requires_maker_checker, "
            "idempotency_key, memo, latitude, longitude, location_name, ip_address, created_at, updated_at) VALUES ("
            f"{sql_quote(row['id'])}, {sql_quote(row['from'])}, {sql_quote(row['to'])}, 'TRANSFER', 'INTRA_BANK', "
            f"'PHP', {row['amount']:.4f}, {row['before']:.4f}, {row['after']:.4f}, 'POSTED', 0, 0, "
            f"{sql_quote(row['id'])}, {sql_quote(row['memo'])}, {sender['lat']:.6f}, {sender['lon']:.6f}, "
            f"{sql_quote(sender['loc'])}, {sql_quote(sender['ip'])}, {fmt_oracle(row['ts'])}, {fmt_oracle(row['ts'])});"
        )
    lines.append("")
    lines.append("COMMIT;")
    lines.append("")
    (OUT / "04_seed_analytics_2026.sql").write_text("\n".join(lines), encoding="utf-8")


def risk_block(events: list[dict]) -> str:
    blocks = []
    for acct in CUSTOMERS:
        person = PEOPLE[acct]
        outs = [row for row in events if row["from"] == acct and row["ts"] <= AS_OF]
        if not outs:
            raise SystemExit(f"no risk history for {acct}")
        last = max(outs, key=lambda row: row["ts"])
        average = round(sum(row["amount"] for row in outs) / len(outs), 2)
        largest = max(row["amount"] for row in outs)
        paid: dict[str, int] = defaultdict(int)
        for row in outs:
            paid[row["to"]] += 1
        peers = [acct for acct, _count in sorted(paid.items(), key=lambda item: (-item[1], item[0]))]
        age_days = (datetime(2026, 10, 12) - datetime(2025, 8, 15)).days
        keys = [person["user_id"], acct, person["account_number"]]
        profile = {
            "user_id": person["user_id"],
            "account_id": acct,
            "account_number": person["account_number"],
            "full_name": person["name"],
            "kyc_status": "VERIFIED",
            "account_age_days": age_days,
            "average_transfer_amount": average,
            "largest_outgoing_on_or_before_2026_10_11": largest,
            "spike_ratio_if_next_equals_largest": round(largest / average, 2),
            "daily_transfer_limit": person["limit"],
            "current_balance_on_2026_10_11": None,
            "home_coordinates": {
                "latitude": person["lat"],
                "longitude": person["lon"],
                "label": person["loc"],
            },
            "last_transaction": {
                "transaction_id": last["id"],
                "amount": float(last["amount"]),
                "timestamp": fmt_iso(last["ts"]),
                "coordinates": {
                    "latitude": person["lat"],
                    "longitude": person["lon"],
                    "label": person["loc"],
                },
            },
            "typical_counterparties": peers or [BILLS],
            "lookup_keys": keys,
        }
        # Balance after the last event on or before the anchor, including incoming.
        running = person["opening"]
        for row in events:
            if row["ts"] > AS_OF:
                break
            if row["from"] == acct:
                running -= row["amount"]
            elif row["to"] == acct:
                running += row["amount"]
        profile["current_balance_on_2026_10_11"] = running
        person["_risk"] = profile
        rendered = json.dumps({k: profile[k] for k in profile if k != "lookup_keys"}, indent=4)
        for key in keys:
            blocks.append(f"    {json.dumps(key)}: {rendered},\n")
    header = (
        "# Paste these keys into CUSTOMER_PROFILES in backend/risk-service/app/seed_data.py.\n"
        "# The risk service does not read Oracle. Same home GPS on every send.\n"
        "# Anchor is the latest outgoing transfer on or before 2026-10-11.\n"
        "# December rows stay in the ledger for the yearly chart. They are not the risk anchor.\n\n"
        "CUSTOMER_PROFILES_2026 = {\n"
    )
    return header + "".join(blocks) + "}\n"


def write_analytics(events: list[dict]) -> dict:
    catalog = {"asOf": "2026-10-12", "year": YEAR, "weekBounds": ["1-7", "8-14", "15-21", "22-end"], "customers": []}
    for acct in CUSTOMERS:
        person = PEOPLE[acct]
        months = []
        for month in range(1, 13):
            weeks = []
            for week in range(4):
                rows = [
                    row for row in events
                    if row["month"] == month and row["week"] == week and acct in (row["from"], row["to"])
                ]
                rows.sort(key=lambda row: row["ts"])
                received = sum(row["amount"] for row in rows if row["to"] == acct)
                sent = sum(row["amount"] for row in rows if row["from"] == acct)
                items = []
                for row in rows:
                    inbound = row["to"] == acct
                    other = PEOPLE[row["from"] if inbound else row["to"]]
                    items.append({
                        "name": other["name"],
                        "initial": other["initial"],
                        "avatarBg": f"Color({other['color']})",
                        "time": fmt_ui_time(row["ts"]),
                        "amount": row["amount"],
                        "isReceived": inbound,
                        "status": "RECEIVED" if inbound else "COMPLETED",
                        "transactionId": row["id"],
                        "memo": row["memo"],
                    })
                start, end = week_bounds(month, week)
                weeks.append({
                    "label": f"Week {week + 1}",
                    "shortLabel": f"W{week + 1}",
                    "dateSubtitle": fmt_subtitle(month, week),
                    "received": received,
                    "sent": sent,
                    "inTransfers": sum(1 for row in rows if row["to"] == acct),
                    "outTransfers": sum(1 for row in rows if row["from"] == acct),
                    "net": received - sent,
                    "transactions": items,
                })
            months.append({
                "monthName": f"{MONTHS[month - 1]} {YEAR}",
                "shortName": SHORT[month - 1],
                "monthIndex": month - 1,
                "received": sum(week["received"] for week in weeks),
                "sent": sum(week["sent"] for week in weeks),
                "net": sum(week["net"] for week in weeks),
                "weeks": weeks,
            })
        catalog["customers"].append({
            "userId": person["user_id"],
            "accountId": acct,
            "accountNumber": person["account_number"],
            "fullName": person["name"],
            "home": person["loc"],
            "yearReceived": sum(month["received"] for month in months),
            "yearSent": sum(month["sent"] for month in months),
            "yearNet": sum(month["net"] for month in months),
            "months": months,
        })
    (OUT / "analytics_catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")
    return catalog


def write_readme(events: list[dict], balances: dict, lowest: dict, catalog: dict) -> None:
    def year_line(acct: str) -> str:
        cust = next(item for item in catalog["customers"] if item["accountId"] == acct)
        return (
            f"| {PEOPLE[acct]['name']} | {cust['yearReceived']:,} | {cust['yearSent']:,} | "
            f"{cust['yearNet']:,} | {PEOPLE[acct]['opening']:,} | {balances[acct]:,} | {lowest[acct]:,} |"
        )

    month_sections = []
    for acct in CUSTOMERS:
        cust = next(item for item in catalog["customers"] if item["accountId"] == acct)
        rows = ["| Month | W1 in/out | W2 in/out | W3 in/out | W4 in/out | Month in | Month out | Net |",
                "|---|---:|---:|---:|---:|---:|---:|---:|"]
        for month in cust["months"]:
            cells = [f"{week['received']:,}/{week['sent']:,} ({week['inTransfers']}/{week['outTransfers']})" for week in month["weeks"]]
            rows.append(
                f"| {month['shortName']} | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} | "
                f"{month['received']:,} | {month['sent']:,} | {month['net']:,} |"
            )
        month_sections.append(f"### {cust['fullName']}\n\n" + "\n".join(rows))

    risk_lines = []
    for acct in CUSTOMERS:
        risk = PEOPLE[acct]["_risk"]
        last = risk["last_transaction"]
        risk_lines.append(
            f"- {risk['full_name']} `{risk['user_id']}` / `{acct}` / `{risk['account_number']}`: "
            f"age {risk['account_age_days']} days, average outgoing PHP {risk['average_transfer_amount']:,.2f}, "
            f"largest outgoing through 11 Oct PHP {risk['largest_outgoing_on_or_before_2026_10_11']:,} "
            f"({risk['spike_ratio_if_next_equals_largest']}x average), "
            f"balance on 11 Oct PHP {risk['current_balance_on_2026_10_11']:,}, "
            f"last transfer `{last['transaction_id']}` PHP {last['amount']:,.0f} at {last['timestamp']} "
            f"from {last['coordinates']['label']}."
        )

    text = f"""# 2026 three-customer seed

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
{year_line(PAOLO)}
{year_line(ELENA)}
{year_line(MARCO)}

{chr(10).join(month_sections)}

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

{chr(10).join(risk_lines)}

`server.py` reads only this seeded `last_transaction`. `main.py` uses `previous_latitude`, `previous_longitude`, and `previous_timestamp` from the request when those fields are present. A normal test sends the same home coordinates as the last transfer, with a new clock time.

## What the other IDE should change

1. Run `04_seed_analytics_2026.sql` in schema `fse_user` after `infrastructure/oracle/init.sql`. These ids do not overlap Juan `U1001` / `A2001`.
2. Paste `risk_profiles.py` into `backend/risk-service/app/seed_data.py`. Register each profile under the user id, the account id, and the account number. The service will not see the SQL by itself.
3. Point `aurabank_web/lib/screens/web/analytics/web_analytics_screen.dart` at `analytics_catalog.json` for the signed-in user. The screen still ships one hardcoded `_monthsCatalog`, so the database will not change the chart until that catalog is replaced. Month index 9 is October. Week index 3 is week 4. `net` is received minus sent.
4. Do not insert `available_balance`. It is a generated column. `from_account_id` and `to_account_id` both reference `accounts`. Amount is greater than 0. Status is `POSTED`. Type is `TRANSFER`. `transaction_type` is `INTRA_BANK`.
5. `balance_master.balance_amount` in the SQL is the 31 Dec 2026 close, after every row in this file.

Transfer count: {len(events)}.
"""
    (OUT / "README.md").write_text(text, encoding="utf-8")


def main() -> None:
    events, state = build()
    write_sql(events, state["balances"])
    (OUT / "risk_profiles.py").write_text(risk_block(events), encoding="utf-8")
    catalog = write_analytics(events)
    write_readme(events, state["balances"], state["lowest"], catalog)
    print(f"transfers {len(events)}")
    for acct in CUSTOMERS:
        risk = PEOPLE[acct]["_risk"]
        print(
            acct,
            "close", state["balances"][acct],
            "low", state["lowest"][acct],
            "avg", risk["average_transfer_amount"],
            "max", risk["largest_outgoing_on_or_before_2026_10_11"],
            "last", risk["last_transaction"]["transaction_id"],
            risk["last_transaction"]["timestamp"],
        )


if __name__ == "__main__":
    main()
