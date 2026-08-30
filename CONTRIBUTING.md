# Contributing

Thanks for wanting to help. This project talks to real flight hardware, so the most valuable
contributions are usually the ones backed by an observation on an actual device — a capture, a
byte sequence, a goggles/camera combination nobody has tried yet.

## Start with an issue

**Open an issue before you open a pull request**, and link the PR to it (`Fixes #123` in the PR
description). It does not have to be long — a couple of sentences is fine. It exists so we can
agree on the approach before you spend an evening on it, and so the change is traceable back to
the problem it solves.

An issue first matters most for:

- protocol work (new commands, new byte maps, changed decoding)
- anything touching the USB/AOA, DUML or video pipeline
- new features or UI changes
- anything that needs hardware you own and we may not

Skip the issue only for the obvious small stuff — a typo, a broken link, a dead comment. If you
already wrote the fix before reading this, open the issue anyway and link it; no work is wasted.

**Good bug reports include:** phone model and Android version, goggles model and firmware, camera
model (O4 Lite / O4 Pro / …), what you did, what happened, and what you expected. Logs from
**Settings → Developer mode → `/debug`** are gold — that screen shows the raw frames and DUML
traffic.

## Fork and pull request

This repo takes changes through forks. Nobody pushes to `main` directly.

```sh
# 1. Fork on GitHub, then:
git clone git@github.com:<you>/openfpvcast.git
cd openfpvcast
git remote add upstream https://github.com/1Basco/openfpvcast.git

# 2. Branch off main
git switch -c fix/short-description

# 3. Build and run it (see the README's Build section)
pnpm install
pnpm --filter web build
pnpm exec cap run android

# 4. Push and open a PR against main
git push origin fix/short-description
```

Keep your branch current with `git fetch upstream && git rebase upstream/main`.

**In the pull request, tell us:**

- which issue it closes;
- what hardware you tested on, or that you could not test on hardware — that is fine, say so and
  we will test it, but never claim a hardware validation you did not run;
- for protocol changes, how you established the bytes (a capture, a repeated observation, a device
  that behaves differently), so the confidence grade in [`AGENTS.md`](AGENTS.md) stays honest.

Small, focused PRs get reviewed faster than large ones. If a change grew beyond its issue, split it.

## House rules for the code

[`AGENTS.md`](AGENTS.md) is the working reference — the transport, the DUML packet layout, the
camera byte maps and how confident we are in each. Read the part you are touching before you
change it, and update it in the same PR when your change teaches us something new about the
protocol.

Beyond that:

- Match the surrounding code — naming, structure, comment density. There is no separate style guide.
- Comment the *why*, especially for protocol constants. A magic byte with no story behind it is a
  future bug.
- `pnpm --filter web check` should stay clean for the files you touched.
- Do not add a dependency for something small that the codebase can do itself.
- Never commit keystores, `.env` files, captures containing your location, or anything derived from
  a proprietary app.

## Releases

Maintainers cut releases by pushing a `v*` tag on `main`; GitHub Actions builds and publishes the
signed APK. Contributors do not need to touch versions — nothing in the repo carries a version
number, so there is nothing to bump in your PR.

## License

By contributing you agree that your work is licensed under this project's [MIT license](LICENSE).
