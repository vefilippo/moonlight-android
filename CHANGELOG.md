# Changelog

Pheme is a personal fork of [Artemis Android](https://github.com/ClassicOldSong/moonlight-android)
with microphone streaming and a USB headset driver. Versions follow
[semantic versioning](https://semver.org/) and are tagged `vX.Y.Z`.

## Unreleased

Next version: 1.1.0

### Added

- **Phone keyboard** — type on the streamed PC from a phone's browser, no app to install. Pair
  once by scanning a QR code from the in-stream menu (Advanced Menu → "Phone keyboard"), then reopen the
  bookmarked page whenever a stream runs. Password mode, Enter, Backspace, Tab, Esc and the
  arrow keys are supported, plus shortcuts: sticky Win/Ctrl/Alt/Shift modifiers (arm one, then tap
  a key or type a letter, e.g. Win+X) and one-tap Start, Win+Tab, Win+D, Win+E, Alt+Tab, Alt+F4
  and Task Manager. A Touchpad tab moves the mouse: drag to move, tap to click, two-finger tap to
  right-click, two fingers to scroll, and hold-able Left/Middle/Right buttons for dragging.
  Manage or remove phones in *Settings → Paired phones*. Phone and
  TV must be on the same network.

### Fixed

- **Green blocks after packet loss on Chromecast with Google TV** — the Amlogic HEVC/AV1 decoder
  could keep showing corrupted green areas until a reconnect. Reference-frame invalidation is now
  off for Amlogic decoders detected through low-latency support, so the host sends a fresh
  keyframe instead.

## 1.0.0 — 2026-11-08

First release under the name **Pheme**, based on Artemis Android 20.2.6. It follows
`v20.2.6-mic.1` ("Artemis-mic").

### Added

- **USB headset driver in the app** for devices without kernel USB audio, such as the
  Chromecast with Google TV. It plays game audio and captures the headset microphone for
  streaming, on UAC1 and UAC2 headsets, through a native usbdevfs isochronous engine.
- Headset volume controlled through the headset's own feature unit (hardware volume, with a
  level-to-dB curve), with a fallback to software gain.
- Headset volume wheel support over HID, including on Android TV where the system doesn't
  pass the wheel through.
- Headset volume entry in the in-stream game menu.
- The headset microphone as a microphone source and picker entry. Switching between headset
  and Android audio happens live when the headset is plugged in or removed.
- Xbox-protocol controllers that Android exposes only as keyboard/mouse are claimed by the
  USB controller driver.

### Changed

- The app is renamed **Pheme**, with a refreshed icon: the Artemis wheel with radiating voice
  rings on deep violet. The adaptive icon is now a vector, and the Android TV banner is
  updated. Package IDs are unchanged, so existing installs and Apollo pairings carry over.
- Releases are versioned as Pheme `X.Y.Z` (`versionName` 1.0.0, `versionCode` 58).

### Fixed

- **Stream collapsing to ~0.5 FPS on weak devices.** Every recovered FEC block rebuilt the
  Reed-Solomon matrix with about 124 ms of wasted math on a Chromecast with Google TV. The
  video receive thread fell behind, its socket overflowed and the requested IDR frames were
  lost too. Matrix setup now takes 0.035 ms with a byte-identical result (moonlight-common-c
  `eecd540`). Write-up: [docs/investigations/2026-11-08-fec-lag.md](docs/investigations/2026-11-08-fec-lag.md).
- Four unit-test suites that had drifted from the app (profiles button type, layout
  inflation theme, application startup) pass again. The full suite is green (206 tests).

### Known limits

- Debug-signed builds (`com.limelight.noirdebug`), installed alongside official Artemis.
- On devices whose UDP receive buffer is capped at 256 KB (the Chromecast with Google TV),
  very large bursts can still cause a short hiccup that recovers on its own. A lower
  bitrate makes it rarer.
