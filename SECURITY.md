# Security policy

## Reporting a vulnerability

Email team@snowyghost.com with what you found and how to reproduce it, or use GitHub's
private vulnerability reporting (Security tab, Report a vulnerability). Please don't open
a public issue for a vulnerability. You'll get a reply within a few days.

## Testing

Test only on installations and tailnets of your own, never on other people's data, devices
or apps, and give us reasonable time to fix a problem before you make it public. If you
work in good faith within those limits, we won't bring or support legal action against
you. We can't speak for Tailscale, Apple or Google, whose services have their own rules.

## Supported versions

The latest connector release and the current versions of the Ovenlight apps for iPhone
and Android.

## How fixes reach you

A connector fix comes as a new release, which the connector installs on its own (one up to
1.0.9 doesn't: install a newer release by hand once). It checks daily, and takes a
security fix at its first quiet moment, without the two-day wait other releases have.
`ovenlight doctor` fails on a release with a known security problem, even with updates
off, and says what to do. A build from source doesn't update itself: pull and run the
install script again for fixes. A fix to either phone app comes in an app update.

[docs/security.md](docs/security.md) has the threat model, and what to do when something
goes wrong.
