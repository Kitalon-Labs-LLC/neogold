from __future__ import annotations

import ipaddress
import math
import threading
import time

from app.config import settings
from app.errors import RateLimitedError


class TokenBucket:
    def __init__(self, capacity: float = 20.0, refill_rate: float = 1.0):
        self.capacity = capacity
        self.refill_rate = refill_rate
        self.tokens = capacity
        self.last_updated = time.time()

    def consume(self) -> tuple[bool, int]:
        now = time.time()
        elapsed = now - self.last_updated
        self.last_updated = now
        self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_rate)

        if self.tokens >= 1.0:
            self.tokens -= 1.0
            return True, 0
        else:
            needed = 1.0 - self.tokens
            wait_seconds = max(1, math.ceil(needed / self.refill_rate))
            return False, wait_seconds


class DeviceRateLimiter:
    def __init__(self, capacity: float = 20.0, refill_rate: float = 1.0):
        self.capacity = capacity
        self.refill_rate = refill_rate
        self._buckets: dict[str, TokenBucket] = {}
        self._lock = threading.Lock()

    def check_and_consume(self, device_id: str) -> None:
        with self._lock:
            if device_id not in self._buckets:
                self._buckets[device_id] = TokenBucket(self.capacity, self.refill_rate)
            allowed, retry_after = self._buckets[device_id].consume()

        if not allowed:
            raise RateLimitedError(retry_after=retry_after)

    def reset(self):
        with self._lock:
            self._buckets.clear()


class EnrollmentRateLimiter:
    def __init__(self, max_failures: int = 10, window_seconds: float = 3600.0):
        self.max_failures = max_failures
        self.window_seconds = window_seconds
        self._failures: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def check_ip(self, client_ip: str) -> None:
        now = time.time()
        with self._lock:
            records = self._failures.get(client_ip, [])
            records = [t for t in records if now - t < self.window_seconds]
            self._failures[client_ip] = records

            if len(records) >= self.max_failures:
                oldest = records[0]
                retry_after = max(1, math.ceil(self.window_seconds - (now - oldest)))
                raise RateLimitedError(retry_after=retry_after)

    def record_failure(self, client_ip: str) -> None:
        now = time.time()
        with self._lock:
            records = self._failures.get(client_ip, [])
            records = [t for t in records if now - t < self.window_seconds]
            records.append(now)
            self._failures[client_ip] = records

    def reset(self):
        with self._lock:
            self._failures.clear()


device_limiter = DeviceRateLimiter(capacity=20.0, refill_rate=1.0)
enrollment_limiter = EnrollmentRateLimiter(max_failures=10, window_seconds=3600.0)


def is_ip_trusted(client_ip: str, allowed_ips_str: str) -> bool:
    try:
        ip = ipaddress.ip_address(client_ip)
    except ValueError:
        return False

    for item in allowed_ips_str.split(","):
        item = item.strip()
        if not item:
            continue
        try:
            if "/" in item:
                if ip in ipaddress.ip_network(item, strict=False):
                    return True
            else:
                if ip == ipaddress.ip_address(item):
                    return True
        except ValueError:
            continue
    return False


def get_client_ip(scope: dict) -> str:
    client = scope.get("client")
    direct_ip = client[0] if client else "127.0.0.1"

    headers_dict = {}
    for k, v in scope.get("headers", []):
        headers_dict[k.lower()] = v.decode("latin1", errors="replace")

    xff = headers_dict.get(b"x-forwarded-for".decode("ascii"))
    if xff and is_ip_trusted(direct_ip, settings.FORWARDED_ALLOW_IPS):
        parts = [p.strip() for p in xff.split(",") if p.strip()]
        if parts:
            return parts[0]

    return direct_ip
