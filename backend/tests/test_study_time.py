from __future__ import annotations

import datetime
import json
from uuid import uuid4
from zoneinfo import ZoneInfoNotFoundError

import pytest
from fastapi.testclient import TestClient

from app import study_time
from tests.conftest import mint_device_token

# 19:00 UTC on 2 February is 01:00 on 3 February in Dhaka (UTC+6): the two calendar days differ.
LATE_EVENING_UTC = datetime.datetime(2027, 2, 2, 19, 0, tzinfo=datetime.UTC)


def test_the_study_day_is_the_day_in_the_study_time_zone_not_the_utc_day():
    assert LATE_EVENING_UTC.date() == datetime.date(2027, 2, 2)
    assert study_time.study_today("Asia/Dhaka", LATE_EVENING_UTC) == datetime.date(2027, 2, 3)
    assert study_time.study_today("UTC", LATE_EVENING_UTC) == datetime.date(2027, 2, 2)
    assert study_time.study_today("America/Los_Angeles", LATE_EVENING_UTC) == datetime.date(2027, 2, 2)
    assert study_time.study_today("Pacific/Kiritimati", LATE_EVENING_UTC) == datetime.date(2027, 2, 3)


def test_an_unknown_zone_name_is_an_error_not_a_guess():
    with pytest.raises(ZoneInfoNotFoundError):
        study_time.study_today("Not/AZone", LATE_EVENING_UTC)


def test_doc7_dates_a_participant_created_at_01_00_in_dhaka_is_dated_that_day(
    client: TestClient, db, enrolled_device, test_study_and_site, monkeypatch
):
    """TC-API-26 / doc 7 Section 1: a server-assigned `created_on` is the study's calendar day, not the UTC day."""
    device_id, privkey, _jwk = enrolled_device
    real = study_time.study_today
    monkeypatch.setattr("app.routers.participants.study_today", lambda zone: real(zone, LATE_EVENING_UTC))

    participant_id = uuid4()
    path = f"/participants/{participant_id}"
    body = json.dumps(
        {"participant_code": "SKP-2703", "consent_form_id": "CF-2703", "consent_date": "2027-02-03"},
        separators=(",", ":"),
    ).encode("utf-8")
    token = mint_device_token(privkey, device_id, "PUT", path, body_bytes=body)

    response = client.put(
        path,
        content=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": str(uuid4()),
            "X-NeoGold-App-Version": "1.0.0",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 201, response.text
    assert response.json()["created_on"] == "2027-02-03"
