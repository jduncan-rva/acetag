---
type: Decision
title: Why AceTag doesn't talk to Anycubic's cloud
description: The cloud API is real and works, but never reports tag UIDs, so it can't name the spool it's showing you.
tags: [decision, anycubic, api, rejected]
status: stable
generated: { by: claude-code/opus-5, at: 2026-08-12T12:45:15Z }
stale_after: 2027-08-12
sources:
  - id: ha-anycubic-cloud
    resource: https://github.com/WaresWichall/hass-anycubic_cloud
    title: hass-anycubic_cloud — reverse-engineered Anycubic cloud client
    author: human:WaresWichall
    last_modified: 2026-08-12
  - id: slicer-conf
    resource: ~/Library/Application Support/AnycubicSlicerNext/AnycubicSlicerNext.conf
    title: Anycubic Slicer Next 1.4.1.1 config, macOS
---

# Why AceTag doesn't talk to Anycubic's cloud

Investigated August 2026, decided against. This exists so it doesn't get re-investigated from
scratch: the API is real, it works, and it still isn't worth having.

## What was being chased

Two things the cloud knows that AceTag doesn't:

- **What's loaded in the ACE right now** — which would let the app show you the four slots, and
  confirm that a sticker you wrote is actually readable by the printer.
- **Print history** — which would let consumption update itself instead of you tapping "used it
  up".

Both are attractive. Neither survives contact with what the API actually returns.

## What exists

No official API and no published documentation. There are two undocumented surfaces, both
reverse-engineered by the Home Assistant community — [WaresWichall/hass-anycubic_cloud][ha] is the
most complete client and the source for everything below.

**Cloud REST**, base `cloud-universe.anycubic.com`, auth via `uc.makeronline.com`. Relevant
endpoints: `GET /v2/printer/all` for printer and slot state, `GET /v2/project/printHistory` for
history.

**Local MQTT** over LAN, on the Kobra 3 / S1 / Kobra X. No cloud account, no root. Push updates
rather than polling.

## Why not

**The tag UID never comes back.** This is the one that kills it. A slot is reported as:

```
index, sku, material_type, color[r,g,b], status, edit_status
```

That is the tag payload *minus its identity*. The ACE tells you it read "PLA, black, SKU
whatever" — not which sticker said so. AceTag's entire model is that the spool is the row and the
UID is what distinguishes three black PLAs from each other (see AGENTS.md, the tag-matching rule).
Matching a slot to a row by spec is exactly the inference that was tried before and that silently
merged separate spools. So the honest version of "what's in your ACE" is *"some PLA Black is in
slot 2"*, plus a disambiguation tap whenever more than one row matches — a confirmation dialog to
tell you something you already knew, on the screen you were already looking at. Print history
inherits the same defect one layer down: it can attribute filament to a *slot*, never to a spool.

**Print history is unmapped.** `printHistory` is implemented in the HA client but the response is
only dumped to the debug log — nobody has modelled the fields. Anything built on it starts with a
reverse-engineering session of its own.

**Auth has no supported path from a phone.** There is no username/password login anywhere in the
API; it's token-only, and you extract the token yourself:

- *Slicer token* (the one that unlocks MQTT) is documented as plaintext `access_token` in
  `AnycubicSlicerNext.conf`. As of Slicer Next 1.4.1.1 on macOS that whole `anycubic_cloud`
  section is encrypted — obfuscated key names, a 1688-char blob, and the string `access_token`
  appears nowhere in the slicer's files. Getting it now means pulling an AES key out of the
  binary.
- *Web token* is `localStorage["XX-Token"]` after logging into the cloud site. Reachable from
  Android by hosting Anycubic's own login page in a WebView and reading it back after login, which
  is a real login screen and not a bad experience — but it's REST only, no MQTT, and tokens still
  expire into a re-login prompt.

Reading it out of the installed Anycubic Android app is not an option on an unrooted phone:
`run-as` refuses release builds, `adb backup` of app data is gone on Android 12+, and proxying
needs the app to trust a user CA.

**Every request impersonates an official client.** The signature is an MD5 over app id, secret,
version and nonce, with all of those hardcoded constants lifted from Anycubic's own web/app/slicer
builds. There is no registration and no key you can ask for. Anycubic rotating any of them breaks
the feature silently, and the only fix is shipping a new AceTag build.

## The shape of the trade

A WebView login, a hardcoded credential set that can be revoked without notice, a polling client,
and an unmapped endpoint to reverse-engineer — in exchange for a slot display that can't name the
spool it's showing you. That is a large, fragile dependency bolted onto an app whose whole point is
that it needs nothing but the phone in your hand.

If this is ever revisited, the thing to check first is whether the ACE has started reporting tag
UIDs. Until it does, the feature can't say the one sentence worth saying.

[ha]: https://github.com/WaresWichall/hass-anycubic_cloud
