# Security policy

## Reporting a vulnerability

Email team@snowyghost.com with what you found and how to reproduce it. Once this
repository is public, GitHub's private vulnerability reporting works too (Security tab,
Report a vulnerability). Please don't open a public issue for a vulnerability. You'll
get a reply within a few days.

## Testing

Test only on installations and tailnets of your own, never on other people's data, devices
or apps, and give us reasonable time to fix a problem before you make it public. If you
work in good faith within those limits, we won't bring or support legal action against
you. We can't speak for Tailscale or Apple, whose services have their own rules.

## Supported versions

The latest connector release and the current version of the Ovenlight iPhone app.

## How fixes reach you

A connector fix comes as a new release, and `ovenlight doctor` then fails on any earlier
release with the problem, saying what to install. A build from source doesn't check:
pull and run the install script again for fixes. An iPhone app fix comes in an app
update.

[docs/security.md](docs/security.md) has the threat model, and what to do when something
goes wrong.
