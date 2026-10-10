import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1] / "contracts"


def load(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.activation_schema = load("activation.schema.json")
        self.permit_schema = load("permit.schema.json")
        self.activation = {
            "accountId": "00000000-0000-4000-8000-000000000001",
            "deviceName": "Test phone", "platform": "android", "appBuild": 40,
            "publicKey": {"kty": "EC", "crv": "P-256", "x": "A" * 43, "y": "B" * 43},
            "challengeId": "00000000-0000-4000-8000-000000000002",
            "proof": "header.payload.signature"
        }
        # Shape-only fixtures: not real public keys, signed proofs or permits.
        self.permit = {
            "v": 1, "iss": "qareeb-activation", "aud": "qareeb-local-control",
            "jti": "00000000-0000-4000-8000-000000000003",
            "sub": "00000000-0000-4000-8000-000000000004",
            "accountId": self.activation["accountId"], "cnf": {"jkt": "C" * 43},
            "iat": 1800000000, "nbf": 1800000000, "exp": 1800604800,
            "recheckAt": 1800086400, "entitlementExpiresAt": 1801000000,
            "features": ["mouse", "keyboard"]
        }

    def valid(self, schema, value):
        return Draft202012Validator(schema, format_checker=FormatChecker()).is_valid(value)

    def test_schemas_well_formed(self):
        Draft202012Validator.check_schema(self.activation_schema)
        Draft202012Validator.check_schema(self.permit_schema)

    def test_activation_platforms(self):
        for platform in ("android", "ios"):
            self.activation["platform"] = platform
            self.assertTrue(self.valid(self.activation_schema, self.activation))

    def test_no_client_identity_or_entitlement_injection(self):
        for field in ("userId", "role", "verified", "seats", "trialEndsAt"):
            value = {**self.activation, field: "injected"}
            self.assertFalse(self.valid(self.activation_schema, value), field)

    def test_no_device_private_key(self):
        self.activation["publicKey"]["d"] = "private"
        self.assertFalse(self.valid(self.activation_schema, self.activation))

    def test_activation_requires_proof_and_challenge(self):
        for field in ("proof", "challengeId"):
            value = copy.deepcopy(self.activation)
            del value[field]
            self.assertFalse(self.valid(self.activation_schema, value))

    def test_activation_bounds(self):
        for field, invalid in (("deviceName", "x" * 81), ("appBuild", 0),
                               ("platform", "web"), ("accountId", "not-a-uuid"),
                               ("proof", "not-a-jws")):
            self.assertFalse(self.valid(self.activation_schema, {**self.activation, field: invalid}))

    def test_permit_shape(self):
        self.assertTrue(self.valid(self.permit_schema, self.permit))

    def test_permit_required_binding(self):
        for field in ("cnf", "sub", "exp", "aud", "jti"):
            value = copy.deepcopy(self.permit)
            del value[field]
            self.assertFalse(self.valid(self.permit_schema, value))

    def test_wrong_issuer_audience_version(self):
        for field, value in (("iss", "attacker"), ("aud", "auth"), ("v", 2)):
            self.assertFalse(self.valid(self.permit_schema, {**self.permit, field: value}))

    def test_permit_unknown_or_duplicate_feature(self):
        for features in (["mouse", "cloud_control"], ["mouse", "mouse"], []):
            self.assertFalse(self.valid(self.permit_schema, {**self.permit, "features": features}))

    def test_permit_no_personal_or_pairing_data(self):
        for field in ("email", "password", "pairingToken", "privateKey"):
            self.assertFalse(self.valid(self.permit_schema, {**self.permit, field: "secret"}))

    def test_policy_confirmed_vs_unresolved(self):
        policy = load("policy.json")
        self.assertEqual(policy["confirmed"]["trial_phone_seats"], 1)
        self.assertEqual(policy["confirmed"]["base_phone_seats"], 1)
        self.assertEqual(policy["confirmed"]["recheck_seconds"], 86400)
        self.assertEqual(policy["confirmed"]["max_offline_seconds"], 604800)
        self.assertTrue(all(value is None for value in policy["unresolved"].values()))

    def test_routes_unique_and_explicitly_authorized(self):
        api = load("api.json")
        identities = [(r["method"], r["path"]) for r in api["routes"]]
        self.assertEqual(len(identities), len(set(identities)))
        for route in api["routes"]:
            self.assertTrue(route["permission"])
            if "/admin/" in route["path"]:
                self.assertIn("aal2", route["permission"])
            if route["permission"] == "public":
                self.assertEqual(route["path"], "/v1/version-policy")
            if route["method"] in ("POST", "DELETE"):
                self.assertTrue(route.get("idempotent"))

    def test_product_policy_is_server_and_admin_managed(self):
        authority = load("policy.json")["authority"]
        self.assertFalse(authority["client_values_are_authoritative"])
        self.assertTrue(authority["audit_required"])
        self.assertEqual(authority["source"], "server_published_policy_version")
        routes = load("api.json")["routes"]
        publication = next(r for r in routes if r["path"] == "/v1/admin/policies/{id}/publish")
        self.assertEqual(publication["permission"], "admin_owner_aal2_recent_auth")


if __name__ == "__main__":
    unittest.main()
