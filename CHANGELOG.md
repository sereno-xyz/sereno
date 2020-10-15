# Changelog #

## v1-2020-SNAPSHOT

**Unreleased**

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


## v1-2020.10.11-0

- Add contact verification process.
- Add single contact unsubscribe link to notification emails.
- Add complete unsubscribe link to notification emails.
- Fix many issues in contact creation.
- Prevent duplicate contact creation.
- Rework internal email sending.
- Integrate with AWS SES bounce/complaint notifications.
- Fix some issues on profile limits and quota section.


## v1-2020.10.07-0

- Initial release
