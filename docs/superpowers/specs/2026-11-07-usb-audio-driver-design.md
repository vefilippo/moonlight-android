# USB headset audio driver for Artemis — design

Date: 2026-11-07
Status: approved by the user (2026-11-07)

## Intent

Some Android devices ship a kernel without the USB audio driver. The user's
Chromecast with Google TV 4K (Android 14, kernel 5.15, `CONFIG_SND_USB_AUDIO`
not set, no loadable module) recognises a plugged-in Logitech G435 LIGHTSPEED
receiver as "has audio playback + capture" but logs
`UsbAlsaManager: cannot find sound card`; the headset only works as an HID
device. No app can reach it through Android's audio stack.

Artemis should drive such headsets itself, in userspace, the way it already
drives Xbox controllers that the kernel doesn't support
(`binding/input/driver/UsbDriverService`). The user experience the user asked
for: **plug in the headset, open Artemis, start a stream, and audio just
works** — game audio in the headset, and the headset microphone feeding the
existing microphone-streaming feature.

## Goals

1. **Any USB headset** that follows the USB Audio Class standard (UAC1 and
   UAC2), on any Android device Artemis runs on — not a G435-specific hack.
2. **Fallback only.** Artemis's driver is used only when Android has no driver
   for the headset. If Android exposes the headset as an audio device
   (`AudioDeviceInfo` of type `TYPE_USB_HEADSET`/`TYPE_USB_DEVICE`), Artemis
   leaves it alone and the normal Android audio path is used. (User confirmed.)
3. **Game audio playback** to the headset during a stream.
4. **Microphone capture** from the headset into the existing microphone
   streaming pipeline (`MicrophoneCaptureManager` → `MoonBridge.queueMicrophonePcm`,
   Opus-encoded and sent AES-GCM-encrypted to Apollo). Used whenever the
   existing "Enable microphone" setting is on.
5. **No setup.** No device picker, no extra toggles. Detection is automatic.

## Non-goals

- Supporting headsets that need vendor-specific (non-UAC) protocols.
- Replacing Android's own USB audio support where it exists.
- Audio outside a streaming session (e.g. the settings mic preview may use it,
  but system-wide audio for other apps is out of scope — not possible anyway).
- MIDI, multichannel/surround beyond what the headset's stereo endpoint offers,
  high-resolution formats.
- iOS/other clients; Apollo-side changes (none should be needed — the host
  already receives game audio requests and client microphone packets).

## Anti-goals (would count as failure even if it "works")

- Breaking or changing behaviour for users whose headsets Android already
  supports (phones with USB-C headsets, most TV boxes).
- Stealing the headset's HID interface: the G435's volume wheel/buttons and
  other HID functions must keep working through Android. (Revised for
  Android TV only, see "Headset wheel on Android TV via HID takeover".)
- Noticeably worse latency or crackling compared with Artemis's normal
  AudioTrack path.
- Crashing or hanging the stream when the headset is unplugged, turned off,
  or misbehaves — failure must fall back to the normal audio path silently.
- Repeated permission prompts on every stream.

## Expected experience (agreed with the user)

- **First use:** Android shows its own USB permission dialog ("Allow Artemis to
  access G435…"). This is unavoidable; it must be possible to remember the
  choice so the user does not see it again for that headset.
- **Automatic pickup:** if a supported, Android-unsupported headset is present
  when a stream starts, game audio goes to it. Otherwise audio goes where it
  goes today (TV/HDMI).
- **Microphone:** the headset mic is used when "Enable microphone" is on; no
  new setting.
- **Volume:** the headset's own volume control should work. On the G435 the
  wheel is HID consumer control (arrives in Android as volume key events), not
  a hardware gain, so Artemis must apply the change itself while its driver
  owns the headset (UAC feature-unit volume if the device has one, otherwise
  software gain), instead of changing the TV's volume.
- **Hot-unplug mid-stream:** audio falls back to the normal path without
  dropping the stream; re-plugging may resume on the headset.

## Constraints and known facts

- Android's Java USB API (`UsbDeviceConnection`, `UsbRequest`) does not support
  **isochronous** transfers, which USB audio streaming uses. The streaming
  part must be native (NDK), using the file descriptor from
  `UsbDeviceConnection.getFileDescriptor()` — no root needed. Options: vendor
  libusb (LGPL-2.1, compatible with Artemis's GPLv3) and wrap the fd with
  `libusb_wrap_sys_device` + `LIBUSB_OPTION_NO_DEVICE_DISCOVERY`, or issue
  `usbdevfs` ioctls directly. Native code builds with ndk-build
  (`app/src/main/jni/*/Android.mk`, NDK 27, ABIs x86/x86_64/armeabi-v7a/arm64-v8a).
- Only the audio interfaces (class 1: AudioControl, AudioStreaming) are
  claimed; HID (class 3) is left to the kernel.
- The Chromecast is 32-bit userspace (armeabi-v7a), Android 14 / API 34.
  `minSdk` 21, `targetSdk` 34.
- Artemis's audio output interface is `nvstream.av.audio.AudioRenderer`
  (`setup(AudioConfiguration, sampleRate, samplesPerFrame)`, `start`, `stop`,
  `playDecodedAudio(short[])`, `cleanup`). Today `Game` always constructs
  `AndroidAudioRenderer`. Decoded audio arrives as 16-bit interleaved PCM at
  48 kHz in 5 ms/10 ms frames; the user's stream uses stereo.
- The existing mic pipeline expects 48 kHz mono 16-bit PCM pushed via
  `MoonBridge.queueMicrophonePcm(short[], int)`, after
  `MoonBridge.setupMicrophoneEncoder(48000, 1, bitrate)` and
  `MoonBridge.startMicrophoneStreaming()`.
- Headsets may expose only some formats/rates (e.g. 48 kHz stereo out, 16 or
  48 kHz mono in, 16-bit or 24-bit samples) and may use asynchronous endpoints
  with a feedback endpoint. Rate and format conversion must happen in Artemis.
- Hardware actually on hand for testing: the user's Chromecast with Google TV
  4K and a Logitech G435 receiver (VID 046d, PID 0adf; interfaces: one HID,
  one AudioControl, two AudioStreaming). Its exact UAC version and endpoint
  layout have not been dumped yet. ADB over Wi-Fi to the Chromecast is already
  paired from the user's PC (wireless-debugging port changes per session).
- Apollo (the host) runs on the user's PC; no host changes expected.

## Decided by the user

- Works with any UAC headset, on any Android device — but only as a fallback
  when Android itself has no driver for it.
- "Plug in, open Artemis, start stream, audio just works": automatic pickup,
  mic tied to the existing setting, headset volume control works, graceful
  unplug, one-time permission.
- **Definition of done:** the G435 working on the user's Chromecast (game
  audio in the headset, headset mic reaching the PC, volume wheel, unplug
  fallback, one-time permission). Generic UAC1/UAC2 code paths should be
  written to the standard, but verifying other headsets, simulated-device
  test suites, and regression testing on other Android devices are not
  required for this work.
- **Permission on Android TV (2026-11-07, after the on-device test):** on
  leanback devices (`PackageManager.FEATURE_LEANBACK`) Artemis does not enable
  `UsbAudioAttachActivity`, so nothing pops up at plug-in, and disables it on
  start if an earlier build enabled it. Permission is requested only when a
  stream starts, once per plug-in or reboot. Phones keep enabling the activity
  before the first prompt so the dialog offers "Always". Reason: on the
  Chromecast with Google TV the dialog's "Always" stored no default
  (`dumpsys usb` showed no device preference), and with the activity enabled
  SystemUI's `TvUsbConfirmActivity` appeared on every re-plug with only
  "allow". This replaces "one-time permission" on TV.
- **In-stream headset volume (2026-11-07, after the on-device test):** the
  in-stream game menu (`GameMenu`) gets a "Headset volume" entry, shown only
  while Artemis drives a USB headset. It opens a D-pad-operable slider for the
  same per-headset gain level (0..15, per VID:PID) as the volume wheel and
  shows the percentage. Reason: Android TV routes VOLUME_UP/DOWN to the system
  before dispatch, so the G435 wheel never reaches Artemis there (zero volume
  key events logged). The wheel handling stays for phones.
- **Headset wheel on Android TV via HID takeover (2026-11-07):** on leanback
  devices only, while Artemis has the headset open and a stream has started,
  Artemis claims the one HID interface whose report descriptor has Consumer
  Volume Increment/Decrement in an Input report (no other HID interface is
  ever claimed), reads the wheel from its interrupt endpoint and steps the
  same per-headset gain, showing "Headset volume N%". When the headset is
  closed (stream end, unplug, failure) the interface is released, which hands
  it back to Android's HID driver. This revises the anti-goal "stealing the
  headset's HID interface" **for Android TV only**, where the wheel does
  nothing useful through Android anyway; phones keep the key-event path and
  never have HID taken.

## Calls made by the designer (not discussed with the user)

- **Microphone picker:** the Artemis-driven headset appears as its own entry in
  the existing `list_microphone_device` picker. When the picker is on
  "Default", a connected Artemis-driven headset is used automatically. If the
  user explicitly picked another microphone, that choice wins.
- **Volume keys:** only volume key events coming from the headset's own HID
  device (matched by `KeyEvent` device / USB VID:PID) change the headset gain;
  volume keys from the TV remote keep controlling the TV as today. Headset gain
  is remembered per headset (VID:PID) across sessions; first use starts at a
  moderate level (not 100%), chosen by the builder.
- **Independent of the "USB controller driver" setting:** headset support is
  not gated by `checkbox_usb_driver`; it has its own lifecycle and permission
  handling (it may share helper code with `UsbDriverService`).

## Left to the builder

- libusb vs raw usbdevfs, and how to vendor it.
- Exact detection rule for "Android has no driver" (e.g. device has
  class-1 interfaces but no matching `AudioDeviceInfo` USB device appears).
- How to obtain a remembered permission (manifest `USB_DEVICE_ATTACHED`
  intent filter with a class-1 device filter gives Android's "always open"
  checkbox; `UsbManager.requestPermission` alone does not remember) and how to
  keep that from bothering users whose headsets Android already supports.
- Integration shape: a new `AudioRenderer` implementation selected at stream
  start, a new capture source inside `MicrophoneCaptureManager`, and whether
  the USB device management lives in `UsbDriverService` or a sibling.
- Buffering, clock-drift handling (feedback endpoints / adaptive resampling),
  latency targets.
- Diagnostics (logging descriptors and chosen alt settings is expected, since
  the first real test is on the user's device).
