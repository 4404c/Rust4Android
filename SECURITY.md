# Security policy

## Reporting a vulnerability

**Please don't report security problems in public issues, discussions or pull requests.**

Report them privately with GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/4404c/Rust4Android/security) of this repository.
2. Click **Report a vulnerability** (or use [this direct link](https://github.com/4404c/Rust4Android/security/advisories/new)).
3. Tell us what you found, how to reproduce it, and which R4A version and phone you used.

Only the maintainers can see the report. We'll answer as soon as we can, usually within a week, and keep you updated while we work on a fix. When it's fixed, we'll credit you in the advisory, unless you'd rather stay anonymous.

## Supported versions

R4A is young. Only the **latest release** gets security fixes.

## What counts

Some examples of things we want to hear about:

- a way for a project, a crate or the AI chat to reach files outside the project in safe mode;
- API keys or other secrets that leak (to logs, backups, exported APKs, other apps);
- a way to make R4A install or run a toolchain that isn't the one it verified;
- anything that lets another app control R4A.

Bugs in the Rust compiler or in crates themselves should go to their own projects.
