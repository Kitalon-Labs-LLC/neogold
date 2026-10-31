"""The study's calendar day.

Every `date` field the server assigns is a calendar date in the study's time zone (`upload_policy.time_zone`, Asia/Dhaka by
default), never the UTC date: a consent at 02:00 in Dhaka is still the previous day in UTC (doc 7 Section 1, BUG-2026-094).
"""

from __future__ import annotations

import datetime
from zoneinfo import ZoneInfo


def study_today(time_zone: str, now: datetime.datetime | None = None) -> datetime.date:
    """The calendar date in [time_zone] at [now] (the current instant when omitted). Raises for an unknown zone name."""
    instant = now if now is not None else datetime.datetime.now(datetime.UTC)
    return instant.astimezone(ZoneInfo(time_zone)).date()
