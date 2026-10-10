# Account and licensing decisions

Status: agreed product requirements, not implemented.
Decision date: 2026-10-08.

## Account and trial

- Sign-in is required before use. Email verification is mandatory.
- All Pro features are available for a one-month trial, rather than a permanent
  free tier. Trial begins after email verification and first successful activation.
- One trial per account; proportionate anti-abuse controls remain to be designed.
- Paid subscription is required after the trial. Preserve pairings and settings.
- Trial and paid base phone capacity: one seat each. Every additional phone
  requires a purchased seat. Invitations use available capacity, not a free
  additional seat; invitation trial eligibility remains to be specified.
- Trial duration remains one month in principle; calendar-month/day definition
  and invitation lifetime are unresolved. Proposed 30-day/48-hour values were
  not accepted. backend/contracts/policy.json separates confirmed/open values.
- Optional phone biometrics unlock the app locally; they do not replace account
  verification or license validation.
- Calendar-month versus fixed-day trial duration must be settled explicitly.

## Authority and connectivity

- Product rules are administered through an owner-admin control panel and
  versioned server policies, not client constants. Includes seats, trial,
  invitations and offer/store-product mappings. Store prices must match store
  configuration; a panel edit cannot charge users or rewrite purchases.
- Validate/preview/publish with MFA, recent authentication, effective date,
  explicit scope and audit. Preserve purchased terms for existing subscriptions
  unless an explicit compliant transition is defined. Runtime/panel not built.

- The central activation server owns entitlement state and issues signed permits.
  The app verifies permits but cannot issue or extend licenses.
- Account authorization is separate from local computer pairing and permissions.
- All remote-control commands stay local. No Internet control relay.
- Attempt online entitlement/version verification every 24 hours during use.
- Allow offline use for up to seven days after successful verification, never
  beyond subscription expiry. Require online verification after that deadline.
- Warn ahead of verification deadlines. Activation-service failures are not
  evidence of revocation; honor the cached permit within its validity window.
- Offline revocation cannot be immediate. Clock rollback and replay defenses,
  renewal boundaries and exact expiry behavior require implementation design.

## Versions

- Ordinary updates are recommended, not automatically mandatory.
- The server may enforce a minimum supported version for security/compatibility.
- Announce mandatory-update deadlines where possible.

## Distribution requirement

- Web dashboard scope is admin-only. Customer account, subscription, invitation
  and device management belong in the mobile app; a separate customer web portal
  is not currently in scope. A public support/privacy site remains planned.
- See COMMERCIAL_EXECUTION_PLAN.md for the current completed/pending status and
  delivery order. Supabase-only activation backend is a proposal, not deployed.

- Publish on Google Play and Apple App Store. Current client is native Android;
  an iOS client is required and has not been implemented.
- Account creation and email verification must be available inside the app.
- Shared account/entitlement backend across Android and iOS; verify store purchase
  evidence and lifecycle notifications server-side before granting entitlements.
- Design digital subscription and additional-device products for applicable
  Google Play Billing and Apple In-App Purchase requirements. Do not assume a
  universal external-checkout exemption; regional rules must be reviewed.
- Restore purchases, cancellation/refunds, account deletion, privacy disclosures,
  reviewer access and local-network permission flows are release requirements.
- Additional seats and owner/member invitations were discussed: baseline one
  phones, paid extra seats, independent verified member accounts. Exact billing
  products and replacement/offline-seat semantics remain to be specified.

Policy references reviewed 2026-10-08:
https://support.google.com/googleplay/android-developer/answer/9858738?hl=en
https://developer.apple.com/app-store/review/guidelines/

## Remaining design details

Pro feature inventory, prices and billing intervals, device limits and transfers,
distribution/payment channel, refunds, account recovery/deletion, trial-abuse
rules, permit schema/key rotation, privacy/retention, and failure-mode tests.
TV support remains deferred; focus on computer control.

No account, billing or activation backend is deployed by this document.
