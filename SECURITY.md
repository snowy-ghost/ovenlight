# Security policy

## Reporting a vulnerability

Email team@snowyghost.com with what you found and how to reproduce it. Once this
repository is public, GitHub's private vulnerability reporting works too (Security tab,
Report a vulnerability). Please don't open a public issue for a vulnerability. You'll
get a reply within a few days.

## Supported versions

The latest connector release and the current version of the Ovenlight iPhone app.

## How fixes reach you

A connector fix comes as a new release, and `ovenlight doctor` then fails on any earlier
release with the problem, saying what to install. A build from source doesn't check:
pull and run the install script again for fixes. An iPhone app fix comes in an app
update.

[docs/security.md](docs/security.md) has the threat model, and what to do when something
goes wrong.
