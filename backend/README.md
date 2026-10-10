# Qareeb activation backend

Stage: local contract/design foundation only. No deployed API, database migration,
authentication service or production signing keys exist in this directory yet.
The Windows control server is a separate product component and remains unchanged.

Candidate runtime: Supabase Auth + PostgreSQL + Edge Functions. Hosting selection
is not finalized. Contracts are provider-independent and versioned before client
integration. No control traffic or local pairing secrets belong in this backend.

## Files

- docs/DESIGN.md: data ownership, transactions, request protection and delivery.
- contracts/policy.json: confirmed rules separated from unresolved decisions.
- contracts/api.json: initial API inventory and explicit permission boundaries;
  this is not an OpenAPI document or runnable route configuration.
- contracts/activation.schema.json: strict device activation request shape.
- contracts/permit.schema.json: proposed signed permit payload, not an Auth JWT.
- tests/test_contracts.py: schema/example and contract-consistency tests, not API,
  cryptography, database concurrency or RLS integration tests.

## Local checks

Use a development virtual environment with requirements-dev.txt, then run:

```powershell
python -m unittest discover -s backend/tests -v
```

JSON Schema validation does not verify signatures or prove seat isolation.
Local validation on 2026-10-08: 13 contract tests passed with Python 3.14.
Never issue a permit until authoritative account/device/entitlement checks and
atomic seat allocation succeed. Production endpoints must not be generated from
this inventory without implementing and testing its authorization rules.
