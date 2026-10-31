"""
backend/tests/test_models_extra_forbid.py

Enforces P25 / Doc 8 Section 8.3 / Doc 7 Section 0:
Every Pydantic request and API model must forbid unknown fields (extra="forbid").
A coding mistake that adds or accepts unknown fields is rejected rather than stored.
"""

import inspect

import pytest
from pydantic import BaseModel

from app.models import api as api_models


def find_all_pydantic_models(module):
    models = []
    for name, obj in inspect.getmembers(module):
        if inspect.isclass(obj) and issubclass(obj, BaseModel) and obj is not BaseModel:
            models.append((name, obj))
    return models


ALL_API_MODELS = find_all_pydantic_models(api_models)


@pytest.mark.parametrize("name,model_cls", ALL_API_MODELS)
def test_pydantic_model_forbids_extra_fields(name, model_cls):
    """Assert that extra="forbid" is explicitly configured on every Pydantic API model."""
    extra = model_cls.model_config.get("extra")
    assert extra == "forbid", f"Model {name} must have model_config['extra'] == 'forbid', but found {extra!r}"


def test_request_model_rejects_unknown_payload_fields():
    """Verify runtime behavior: passing an extra field to EnrollmentRequest raises a ValidationError."""
    from pydantic import ValidationError

    from app.models.api import EnrollmentRequest

    valid_jwk = {"kty": "EC", "crv": "P-256", "x": "a" * 43, "y": "b" * 43}
    valid_device = {
        "manufacturer": "Transsion",
        "model": "Spark 10",
        "android_api": 33,
        "app_version": "1.0.0",
        "camera_hardware_level": "FULL",
        "qualification": "QUALIFIED",
    }
    valid_payload = {
        "enrollment_code": "2345-6789-BCDF",
        "device_public_key": valid_jwk,
        "device": valid_device,
    }

    # Should succeed with valid payload
    req = EnrollmentRequest(**valid_payload)
    assert req.enrollment_code == "2345-6789-BCDF"

    # Should fail when an unknown field is supplied
    invalid_payload = {**valid_payload, "unexpected_extra_field": "malicious_payload"}
    with pytest.raises(ValidationError) as exc_info:
        EnrollmentRequest(**invalid_payload)
    assert "extra_forbidden" in str(exc_info.value)
