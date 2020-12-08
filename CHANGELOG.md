# Changelog #


## v1.2 (unreleased)

- Add SSL Certificate monitoring.
- Add discord webhooks integration.
- Add telegram bot api and webhooks integration.
- Enable query requests use GET http method.
- Fix minor issue with user activation when is logged in using
  external authententication provider.
- Internal refactor of contacts.
- Minor interface restyling.
- Monitor list minor restyling.
- Rewrite result handling on monitors.
- Add tooling for easy create multiplatform docker images.
- Normalize primary contacts (now they are permantent, not editable
  and the email address is used from profile).
- Refactor internal monitor downtime cause reporting and
  visualization.


## v1.1

- Properly ignore unhandled events from awssns (webhook).
- Add rate limit default config to devenv nginx.
- Properly handle websocket connection lifecycle.
- Internal naming consistency fixes.
- Many bugfixes related to monitor creation.
- Fix unexpected exception when show monitor detail whithout the first
  monitor run (monitored-at field is `nil`).
- Refactor & Cleanup on select components.
- Add ad-hoc svg icon spread and inline it in template (considerable
  visual performance improvement, prevent icon flickering on page
  change).
- Improve exception handling and error reporting (dev only).
- Fix missing delete token on email notification email.


## v1.0

- Add contact verification process.
- Add single contact unsubscribe link to notification emails.
- Add complete unsubscribe link to notification emails.
- Fix many issues in contact creation.
- Prevent duplicate contact creation.
- Rework internal email sending.
- Integrate with AWS SES bounce/complaint notifications.
- Fix some issues on profile limits and quota section.


## v0.0

- Initial release
