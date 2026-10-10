# Activation design v1 (draft)

## Boundary and decisions

This is design work, not a production server or migration. Supabase is the
candidate. All licenses/seats live outside local PC pairing. Trial duration
and invitation lifetime are explicitly unresolved: do not seed the proposed
30-day/48-hour defaults. The latest explicit decision sets both the trial and
paid base to one phone seat. Every additional phone requires a purchased seat.
Invitations allocate existing capacity; they never grant a free extra seat.
Prices/store SKUs are unresolved.

## Data model

## Admin-managed product policies

Latest requirement: product rules are managed through the admin control panel,
not hardcoded client constants. policy.json records current agreed defaults and
unresolved values only; it is not the runtime authority or an admin UI.

Use versioned policy definitions for base seats, trial capacity/duration,
invitation expiry, replacement rules, offline/recheck intervals and release
compatibility. Default trial and paid capacity is currently one phone each.
Admin may configure saleable offers and verified store-product mappings; prices
and charging still require corresponding store configuration and user consent.
Changing an admin price cannot rewrite a Google/Apple transaction or bill a user.

Only project-owner admins with aal2 and recent authentication can draft/publish;
support and ordinary account owners cannot. Validate server-side positive/bounded
values, recheck <= offline duration, entitlement caps and minimum security limits.
Unresolved rules block the affected offer, not sign-in/recovery or unrelated flows.

Workflow: draft -> validate -> impact preview -> explicit publish. Publish creates
an immutable version with actor/reason/effective date/scope and requires the
expected current version, preventing concurrent admins overwriting each other.
Retry by idempotency key; audit each sensitive change in the same transaction.
Rollback publishes a new version based on an earlier one, never edits history.

Subscriptions and trials snapshot their accepted offer version, capacity terms
and duration when activated/purchased. New offers apply to new purchases by
default. Existing access must not silently lose seats/time due to a policy edit;
changes require defined renewal/effective-date rules, notices and store compliance.
Separate centrally enforced security/version policy from purchased commercial
terms. Already-issued offline permits cannot be recalled instantly by any panel.
Client refreshes account/permit data from the server; PC enforces signed claims.

Add product_policy_versions and offer_versions entities, immutable after publish,
plus subscription/trial references to accepted offer and audited transitions.
Database authorization and UI tests must cover unauthorized policy updates,
invalid combinations, stale publication, duplicate requests, existing subscriber
protection and rollback. None of this runtime/panel behavior is implemented yet.

## Entity inventory

| Entity | Ownership / key constraints |
| --- | --- |
| auth user | Provider-owned verified identity; no second password database |
| accounts | Subscription group; one owner user; independent from admin role |
| memberships | Unique account/user; owner or member; removal timestamps |
| trial claims | One claim per owning user; never reset by device reinstall or deletion flow; privacy/retention policy required |
| subscriptions | Store/provider purchase identity unique across accounts; normalized access period separate from cancel/renew flags |
| entitlements | Authoritative computed features, phone capacity, validity and version |
| devices | Installation UUID, owning user, public key/thumbprint; never private key or hardware tracking ID |
| activations | Account, member and device references bound consistently; one live allocation per installation |
| invitations | Verified destination policy, hashed random token, expiry, reserved seat, accepted/revoked timestamps |
| seat allocations | Account slot held by exactly one invitation reservation OR active device |
| challenges | Purpose/device/account-bound nonce hash, short expiry, consumed once |
| permits | Permit ID/device/key/version/deadlines; signing key ID, not private key |
| signing key metadata | Public key and validity windows; private material in restricted secret/key service |
| store events | Provider/event identity unique; processing status, retries, reconciliation |
| idempotency records | Actor/operation/key unique, request digest and bounded expiry |
| mail outbox | Commit with invitation; delivery retry does not reserve another seat |
| admin grants / audit | Server-managed roles, actor/action/reason; no secrets or raw request dumps |
| version policies | Platform/build requirements and warning deadlines; no entitlement grants |

Timestamps are server UTC. No pairing tokens, desktop certificates/private keys,
typed content or mouse events are stored centrally. Review deletion/financial
retention and anti-abuse identifiers before production data collection.

## Transaction boundaries

Lock the account allocation row before activation, invitation, reservation expiry,
replacement or capacity reduction. Count active devices plus unexpired reserved
slots; allocate and commit once. All operations use the same lock ordering.
Invitation acceptance converts its own reservation, not a second capacity count.
Check destination against server-verified identity using a documented email
comparison policy; never strip plus tags or infer that two emails are equivalent.
Expire reservations inside allocation transactions as well as scheduled cleanup.
Persist an email outbox entry in the same invitation transaction; dispatch later.

Device registration is not activation. Prove the registered key using a fresh,
single-use challenge bound to actor/account/device/purpose. Authorize account
membership on every request. Reuse of the same idempotency key with a different
body returns conflict, not the previous success. A signing failure must allow a
safe retry without a second seat or restarted trial.

## Request layers

1. TLS, bounded body, correlation ID and abuse limits.
2. Authenticate through verified provider credentials; user metadata is not roles.
3. Verified email when needed; fresh device proof for activation/permit requests.
4. Resource authorization, then transactional domain service.
5. Restricted signing service or verified-store adapter, then redacted response.

Signed-in unverified users can inspect verification state and recover access.
Expired subscribers can sign in, manage devices and buy/restore a subscription.
Do not put a blanket paid-subscription check in front of these paths.
Admin needs a server-managed role AND aal2, checked on every sensitive request.
Support cannot issue overrides. Store webhooks use provider verification rather
than a user bearer token. Reject unsupported providers and replayed invalid events.

## Database access

Keep billing/device/signing tables in a private schema, with privileges revoked
from anon/authenticated. Enforce scoped access via server API and least-privilege
transaction functions. Any deliberately exposed tables need explicit grants and
RLS allow/deny tests. A service-role key is backend-only and bypasses RLS: code
using it must still authorize actor and resource; RLS cannot rescue that omission.
Restrict SECURITY DEFINER functions, set a safe search_path, and revoke default
PUBLIC execute. Database runtime tests precede staging exposure.

## Permit contract

Candidate envelope is asymmetric JWS ES256 with fixed allowed algorithm and kid
from a bundled/trusted key set. Validate with maintained crypto libraries, not
manual signature code. Payload schema is proposed and must be tested across
Android, Windows and iOS before freezing. The local PC verifies device possession
with a fresh local challenge bound to its session/identity; no auth JWT or permit
alone grants local pairing. A permit is not a renewal credential.

After signature verification, enforce nbf <= iat < exp, recheckAt <= exp,
exp <= min(iat + 604800, entitlementExpiresAt), and normally
recheckAt = min(iat + 86400, exp). Check trusted server time and persisted clock
rollback state. Account/member/device must remain active before each issuance.
Schemas cannot express/prove these cross-field/time/cryptographic invariants.
The feature list is a proposed inventory, not proof those features are released.

Cached permits survive transient service failure only until exp. Revoking a device
cannot invalidate an already-issued offline permit instantly. Replacement and
capacity reduction must disclose that overlap and apply policy, not promise a
strict real-time simultaneous-device limit. Local emergency stop always remains.

## Next implementation gates

1. Resolve trial/invitation/replacement policies with user; freeze API bodies and errors.
2. Build isolated database migration and concurrency/RLS tests, with no production data.
3. Prove one verified identity, activation challenge and atomic seat allocation.
4. Prove one signed permit in candidate Edge runtime, then Java/Python verification.
5. Only then integrate Android registration and admin web. Keep existing local
   users out of forced-login migration until the backend and recovery are ready.

References checked 2026-10-08:
https://supabase.com/docs/guides/database/postgres/row-level-security
https://supabase.com/docs/guides/functions/auth
https://supabase.com/docs/guides/auth/auth-mfa
