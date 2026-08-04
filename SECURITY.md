# Security Policy

## Reporting a vulnerability

If you find a security issue, please **do not** open a public issue about it.
Instead, report it privately so it can be fixed before it's made public:

- Open a [private security advisory](https://github.com/evanwhitt/hyperion-android-reborn/security/advisories/new) on this repository, or
- Create a GitHub issue with `[SECURITY]` in the title and mark it as sensitive, or
- Email the maintainer (contact via the GitHub profile of [evanwhitt](https://github.com/evanwhitt)).

Please include:

- A description of the vulnerability and the affected version(s)
- Steps to reproduce (or a proof of concept)
- Any impact you believe it has

You'll get an acknowledgment within a few days, and we'll work with you on a fix and disclosure timeline.

## Supported versions

Only the latest tagged release (and the current `latest` build on `main`) receive security fixes. Older releases are not maintained.

## Scope

This project runs on Android devices and talks to a local Hyperion / HyperHDR instance on your network. It requires the `INTERNET` permission and captures screen content. There is no server component or cloud backend.
