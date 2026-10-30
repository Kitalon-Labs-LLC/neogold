from __future__ import annotations

from fastapi.testclient import TestClient


def test_tc_api_01_health(client: TestClient):
    """TC-API-01: Health GET /health -> 200 with server_time and api_version."""
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert "api_version" in data
    assert data["api_version"] == "1.0.0"
    assert "server_time" in data

    # Also test /v1/health prefix
    response_v1 = client.get("/v1/health")
    assert response_v1.status_code == 200
    assert response_v1.json()["status"] == "ok"


def test_responses_carry_x_content_type_options_nosniff(client: TestClient):
    """BUG-2026-084: the API sets the header itself, on success and on an error from the auth layer."""
    ok = client.get("/v1/health")
    assert ok.status_code == 200
    assert ok.headers["x-content-type-options"] == "nosniff"

    # a route that needs a device assertion, called without one: the 401 comes from inside the middleware stack
    denied = client.get("/v1/device")
    assert denied.status_code == 401
    assert denied.headers["x-content-type-options"] == "nosniff"
