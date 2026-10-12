# Paste these keys into CUSTOMER_PROFILES in backend/risk-service/app/seed_data.py.
# The risk service does not read Oracle. Same home GPS on every send.
# Anchor is the latest outgoing transfer on or before 2026-10-11.
# December rows stay in the ledger for the yearly chart. They are not the risk anchor.

CUSTOMER_PROFILES_2026 = {
    "U9001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "A9001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "1000-9000-0001": {
    "user_id": "U9001",
    "account_id": "A9001",
    "account_number": "1000-9000-0001",
    "full_name": "Paolo Reyes",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 2374.42,
    "largest_outgoing_on_or_before_2026_10_11": 4200,
    "spike_ratio_if_next_equals_largest": 1.77,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 198300,
    "home_coordinates": {
        "latitude": 14.5547,
        "longitude": 121.0244,
        "label": "Bonifacio Global City, Taguig"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0326",
        "amount": 2000.0,
        "timestamp": "2026-10-11T09:05:00+08:00",
        "coordinates": {
            "latitude": 14.5547,
            "longitude": 121.0244,
            "label": "Bonifacio Global City, Taguig"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9002"
    ]
},
    "U9002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "A9002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "1000-9000-0002": {
    "user_id": "U9002",
    "account_id": "A9002",
    "account_number": "1000-9000-0002",
    "full_name": "Elena Cruz",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 1667.65,
    "largest_outgoing_on_or_before_2026_10_11": 2625,
    "spike_ratio_if_next_equals_largest": 1.57,
    "daily_transfer_limit": 100000,
    "current_balance_on_2026_10_11": 106250,
    "home_coordinates": {
        "latitude": 14.676,
        "longitude": 121.0437,
        "label": "Diliman, Quezon City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0330",
        "amount": 1687.0,
        "timestamp": "2026-10-11T19:35:00+08:00",
        "coordinates": {
            "latitude": 14.676,
            "longitude": 121.0437,
            "label": "Diliman, Quezon City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "U9003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "A9003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
    "1000-9000-0003": {
    "user_id": "U9003",
    "account_id": "A9003",
    "account_number": "1000-9000-0003",
    "full_name": "Marco Santos",
    "kyc_status": "VERIFIED",
    "account_age_days": 423,
    "average_transfer_amount": 3158.9,
    "largest_outgoing_on_or_before_2026_10_11": 5000,
    "spike_ratio_if_next_equals_largest": 1.58,
    "daily_transfer_limit": 500000,
    "current_balance_on_2026_10_11": 330200,
    "home_coordinates": {
        "latitude": 10.3157,
        "longitude": 123.8854,
        "label": "Cebu Business Park, Cebu City"
    },
    "last_transaction": {
        "transaction_id": "TX-2026-0328",
        "amount": 1200.0,
        "timestamp": "2026-10-11T16:45:00+08:00",
        "coordinates": {
            "latitude": 10.3157,
            "longitude": 123.8854,
            "label": "Cebu Business Park, Cebu City"
        }
    },
    "typical_counterparties": [
        "A8004",
        "A9001"
    ]
},
}
