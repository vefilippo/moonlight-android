# USB Headset Audio Driver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Artemis drives USB Audio Class headsets itself when Android has no driver for them. Game audio plays in the headset, the headset mic feeds microphone streaming, and the headset's volume wheel works. Done means the Logitech G435 works on the user's Chromecast with Google TV.

**Architecture:**
- **Java control plane** in a new package `com.limelight.binding.audio.usb`:
  - parses descriptors (from `UsbDeviceConnection.getRawDescriptors()`)
  - picks formats
  - sends UAC control requests through `controlTransfer`
  - handles permission and lifecycle
  - converts PCM: channels, rate, sample width and gain
- **Native data plane:** a small native library, `libartemis-usbaudio`. It runs isochronous transfers with raw `usbdevfs` ioctls on the connection's file descriptor, through one reaper thread and two ring buffers.
- **Wiring:**
  - `Game` gets a `UsbAudioRenderer` that switches between the headset and the existing `AndroidAudioRenderer` while the stream runs.
  - `MicrophoneCaptureManager` gets a capture source abstraction with a USB-headset implementation.

**Tech Stack:** Java (Android SDK, API 21+), C (ndk-build, NDK 27, Linux `usbdevfs`), JUnit 4, Robolectric 4.16, Mockito 5.

**Spec:** `docs/superpowers/specs/2026-11-07-usb-audio-driver-design.md`

## Global Constraints

- `minSdk` 21, `targetSdk` 34, NDK `27.0.12077973`, ABIs x86/x86_64/armeabi-v7a/arm64-v8a. Chromecast = armeabi-v7a, API 34.
- **No new third-party dependencies.** Use raw `usbdevfs`, not libusb, and no vendoring. License stays GPLv3.
- **Claim only class-1 interfaces** (AudioControl, AudioStreaming). Never claim or touch the HID (class 3) interface.
- **Fallback only.** If `AudioManager.getDevices(GET_DEVICES_ALL)` contains `TYPE_USB_HEADSET`, `TYPE_USB_DEVICE` or `TYPE_USB_ACCESSORY`, Artemis does not claim, prompt for, or touch the headset.
- **API floor (builder's call):** the driver is active only on API ≥ 23, because below that we can't tell whether Android supports the headset. On API 21–22 behaviour is unchanged.
- **No new user settings.** The driver is independent of `checkbox_usb_driver`.
- **Strings** go in `app/src/main/res/values/strings.xml` only. No translations.
- **No Apollo / host changes.**
- **Decoded game audio** arrives as interleaved 16-bit PCM, 48000 Hz, 2/6/8 channels.
- **Mic pipeline input** is 48000 Hz mono 16-bit, passed to `MoonBridge.queueMicrophonePcm(short[], int)`.
- **Robolectric tests must never load `libartemis-usbaudio`.** Only `UsbHeadset` touches `UsbAudioNative`. Everything else depends on the interfaces below, so tests can use fakes.
- **Commands** (run from `C:\claude\Artemis`):
  - Unit tests: `./gradlew :app:testNonRoot_gameDebugUnitTest`
  - Debug APK: `./gradlew :app:assembleNonRoot_gameDebug`, which produces `app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-armeabi-v7a-debug.apk`
  - adb: `$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`. Chromecast at `192.168.178.35`; the wireless-debugging port changes every session, so ask the user for it.
- Python is not installed. Use the Edit tool, not scripts.
- Log tag for everything new: `UsbAudio`, via `LimeLog`.

## Review Focus

1. **Headset unplugged or receiver pulled mid-stream.** The stream keeps running, game audio moves to the TV within one frame, the mic loop ends without an exception, and nothing is logged as a crash. Tests: Task 7 `unplugMidStreamFallsBackToAndroid`, Task 8 `usbSourceReportsEndWhenDeviceGone`.
2. **A headset Android already supports** (phone with a USB-C headset). It is never claimed, no dialog appears, and the attach activity stays disabled. Test: Task 6 `androidSupportedHeadsetIsLeftAlone`.
3. **The user taps "Deny" on the permission dialog.** No further prompt for that headset until it is unplugged and plugged back in, and audio uses the normal path. Tests: Task 6 `deniedHeadsetIsNotPromptedAgainUntilReattached`.
4. **A headset without a 48 kHz / 16-bit stereo output** (44.1 kHz only, 24-bit only, mono output, or capture only). The best available format is picked, the audio is converted, and a missing direction is simply absent. Tests: Task 2 selector cases, Task 3 resampler and packing cases.
5. **A stream configured for 5.1/7.1 surround** while the headset is stereo. The audio is downmixed, not rejected. Tests: Task 3 `downmix51ToStereo`, Task 7 `surroundStreamIsDownmixed`.

---

### Task 1: Descriptor parser (with the real G435 descriptors)

**Files:**
- Create: `app/src/test/resources/usb/g435_descriptors.hex`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UacDescriptors.java` (model: `UacDescriptors`, nested `AltSetting`, `FeatureUnit`)
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UacDescriptorParser.java`
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UacDescriptorParserTest.java`

**Interfaces:**
- **Produces** `final class UacDescriptors`, with public fields:
  - `int uacVersion` (1 or 2)
  - `int controlInterface`
  - `List<AltSetting> altSettings`
  - `List<FeatureUnit> featureUnits`
- **Produces** `static final class UacDescriptors.AltSetting`, with public fields:
  - `int interfaceNumber`, `alternateSetting`
  - `boolean isInput` (endpoint direction IN)
  - `int channels`, `subframeBytes`, `bitResolution`
  - `int[] sampleRates`: discrete rates. Mutable, because Task 5 fills it for UAC2.
  - `int minRate`, `maxRate`: continuous range, 0 if none
  - `int endpointAddress`, `maxPacketBytes`, `bInterval`
  - `int syncType`: 0 none, 1 async, 2 adaptive, 3 sync (bmAttributes bits 2–3)
  - `int feedbackEndpointAddress`: 0 if none
  - `boolean hasSampleRateControl`: UAC1 class-specific endpoint bmAttributes bit 0
  - `int terminalLink`
  - `int clockSourceId`: −1 on UAC1
  - method `boolean supportsRate(int rate)`: true when `rate` is in `sampleRates` or within `[minRate, maxRate]`
- **Produces** `static final class UacDescriptors.FeatureUnit`: `int unitId`; `boolean hasMasterMute`, `hasMasterVolume`.
- **Produces** `static UacDescriptors UacDescriptorParser.parse(byte[] raw)`:
  - returns `null` when there is no AudioStreaming interface with a Type I PCM format
  - never throws on truncated or garbage input; returns what it could parse, or `null`
- **Produces** `static String UacDescriptorParser.toHex(byte[] raw)`, for diagnostics logging.

- [ ] **Step 1: Capture the G435 descriptors.**
  1. Ask the user to plug the G435 receiver into the Chromecast and give the current wireless-debugging port.
  2. Run: `adb connect 192.168.178.35:<port>` then `adb shell 'for d in /sys/bus/usb/devices/*; do [ "$(cat $d/idVendor 2>/dev/null)" = 046d ] && [ "$(cat $d/idProduct)" = 0adf ] && od -An -tx1 -v $d/descriptors; done'`
  3. Save the bytes as one hex string (whitespace allowed) in `g435_descriptors.hex`.
  4. **If SELinux denies the read:** write a synthetic UAC1 headset fixture following the UAC1 spec's example headset descriptors (48 kHz/16-bit stereo OUT adaptive; 16 kHz/16-bit mono IN), save it as `uac1_headset_descriptors.hex`, and use it in the tests below. Replace it with the real dump during Task 10 (Task 6's `UsbHeadset.open` logs the hex).
  - Expected: the file starts with `12 01` (device descriptor).

- [ ] **Step 2: Write the failing tests.** Load the fixture via `getClass().getResourceAsStream("/usb/...")`. Use the values that actually appear in the dump. Where the G435 dump shows other numbers, update the asserted values to match the dump, not the other way round.

```java
@Test public void parsesG435() {
    UacDescriptors d = UacDescriptorParser.parse(fixture("g435_descriptors.hex"));
    assertNotNull(d);
    assertEquals(1, d.uacVersion);                    // expected UAC1; correct per dump
    AltSetting out = firstOutput(d), in = firstInput(d);
    assertNotNull(out); assertNotNull(in);
    assertEquals(2, out.channels);
    assertTrue(out.supportsRate(48000));
    assertEquals(2, out.subframeBytes);
    assertEquals(0, out.endpointAddress & 0x80);
    assertEquals(0x80, in.endpointAddress & 0x80);
    assertNotEquals(out.interfaceNumber, in.interfaceNumber);
}
@Test public void ignoresHidAndZeroBandwidthAlts() { /* no AltSetting with alternateSetting==0; none on the HID interface */ }
@Test public void parsesUac2ClockSourceAndLeavesRatesEmpty() { /* synthetic UAC2: clockSourceId resolved via terminal link, sampleRates.length==0 */ }
@Test public void parsesUac1ContinuousRange() { /* tSamFreqType==0 → minRate/maxRate set, supportsRate(44100) true for 8000..48000 */ }
@Test public void truncatedInputDoesNotThrow() { assertNull(UacDescriptorParser.parse(new byte[]{0x12, 0x01, 0x00})); }
@Test public void nonAudioDeviceReturnsNull() { /* an Xbox-controller-like descriptor set */ }
```

- [ ] **Step 3: Run the tests to verify they fail.** Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests '*UacDescriptorParserTest*'`. Expected: compilation failure (classes missing).

- [ ] **Step 4: Implement `UacDescriptors` and `UacDescriptorParser.parse`.**
  - Walk the descriptors by `bLength`/`bDescriptorType`, tracking the current interface (class 1, subclass 1 = AudioControl; subclass 2 = AudioStreaming).
  - UAC version from the AC header's `bcdADC` (`0x0200` = UAC2).
  - UAC1 Type I format: subframe size, bit resolution, channels, rates.
  - UAC2 `AS_GENERAL` gives channels; `FORMAT_TYPE` gives subframe size and bit resolution. `clockSourceId` comes from the input or output terminal whose ID equals `terminalLink`.
  - The feedback endpoint is the `bSynchAddress` of the data endpoint (UAC1), or the IN endpoint with usage type 1 in the same alt setting (UAC2).
  - Feature units: UAC1 `bmaControls[0]` bit 0 = mute, bit 1 = volume. UAC2: bits 0–1 = mute, bits 2–3 = volume.

- [ ] **Step 5: Run the tests to verify they pass.** Same command. Expected: PASS.

- [ ] **Step 6: Commit.** `git add` the new files; message `usb-audio: UAC1/UAC2 descriptor parser`.

---

### Task 2: Format selection

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UacFormatSelector.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UacStreamConfig.java`
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UacFormatSelectorTest.java`

**Interfaces:**
- **Consumes:** `UacDescriptors.AltSetting` (Task 1).
- **Produces** `final class UacStreamConfig`: `final AltSetting alt; final int sampleRate;` and `int frameBytes()` (= `alt.channels * alt.subframeBytes`).
- **Produces** `static UacStreamConfig UacFormatSelector.selectPlayback(List<AltSetting> alts)` and `static UacStreamConfig selectCapture(List<AltSetting> alts)`. Both return `null` when nothing is usable.

Ranking (decided here):
- **Playback:**
  - rate: 48000 > 96000 > 44100 > highest other supported
  - then channels: 2 > 1 > more
  - then subframe: 2 > 3 > 4 bytes
- **Capture:**
  - rate: 48000 > 16000 > 32000 > 44100 > 24000 > highest other
  - then channels: 1 > 2 > more
  - then subframe: 2 > 3 > 4
- Only output alts are considered for playback, and only input alts for capture.
- **Rejected alts:** `channels == 0`, `subframeBytes` not in {2, 3, 4}, no supported rate.

- [ ] **Step 1: Write the failing tests.** Build `AltSetting` objects directly.

```java
@Test public void prefers48kStereo16() { /* alts: 44.1k/2ch/16, 48k/2ch/24, 48k/2ch/16 → 48k/2ch/16 */ }
@Test public void fallsBackTo441WhenNo48k() { assertEquals(44100, selectPlayback(List.of(alt(OUT, 2, 2, 44100))).sampleRate); }
@Test public void accepts24BitOnly() { assertEquals(3, selectPlayback(List.of(alt(OUT, 2, 3, 48000))).alt.subframeBytes); }
@Test public void acceptsMonoOutput() { assertEquals(1, selectPlayback(List.of(alt(OUT, 1, 2, 48000))).alt.channels); }
@Test public void captureOnlyDeviceHasNoPlayback() { assertNull(selectPlayback(List.of(alt(IN, 1, 2, 16000)))); }
@Test public void capturePrefers48kMonoThen16k() { /* alts IN 16k/1ch and 44.1k/2ch → 16000 */ }
@Test public void continuousRangePicks48k() { /* minRate 8000 maxRate 96000 → 48000 */ }
@Test public void rejectsZeroChannelAndOddSubframe() { /* channels 0, subframe 1 → null */ }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*UacFormatSelectorTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement the selector** with the ranking above.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: playback/capture format selection`.

---

### Task 3: PCM conversion and gain

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/PcmConverter.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/LinearResampler.java`
- Test: `app/src/test/java/com/limelight/binding/audio/usb/PcmConverterTest.java`, `LinearResamplerTest.java`

**Interfaces:**
- **Produces** these `PcmConverter` methods:
  - `static int toDeviceChannels(short[] in, int inFrames, int inChannels, int outChannels, short[] out)`. Returns frames.
    - 6 or 8 input channels are first downmixed to stereo:
      - L = FL + 0.707·C + 0.707·SL (+ 0.707·RL for 7.1)
      - R likewise
      - LFE dropped
      - result clamped to 16 bits
      - channel order is Moonlight's FL, FR, C, LFE, RL, RR, SL, SR
    - Stereo is then mapped to `outChannels`:
      - 1 = average
      - 2 = copy
      - more than 2 = L and R in the first two slots, zeros elsewhere
  - `static int pack(short[] in, int samples, int subframeBytes, float gain, byte[] out)`.
    - Little-endian.
    - The 16-bit sample is left-justified in 3- or 4-byte subframes; the low bytes are 0.
    - Gain is applied, then the result is clamped.
    - Returns bytes written.
  - `static int unpackToMono(byte[] in, int length, int channels, int subframeBytes, short[] out)`.
    - Takes the top 16 bits of each subframe and averages the channels.
    - Returns frames.
  - `static float gainForLevel(int level)` = `(level / 15f)²`, with `level` clamped to 0..15.
  - Constants `static final int MAX_GAIN_LEVEL = 15`, `DEFAULT_GAIN_LEVEL = 9`.
- **Produces** `final class LinearResampler`:
  - constructor `LinearResampler(int inRate, int outRate, int channels)`
  - `int process(short[] in, int inFrames, short[] out)`. Returns the number of output frames.
  - Phase is kept across calls, so consecutive 5 ms chunks join without clicks.
  - Pass-through copy when `inRate == outRate`.
  - `static int maxOutputFrames(int inFrames, int inRate, int outRate)` = `ceil(inFrames * outRate / inRate) + 1`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test public void downmix51ToStereo() { // FL=1000 FR=0 C=1000 LFE=30000 RL=0 RR=0
    short[] out = new short[2];
    assertEquals(1, PcmConverter.toDeviceChannels(new short[]{1000,0,1000,30000,0,0}, 1, 6, 2, out));
    assertEquals(1707, out[0], 1); assertEquals(707, out[1], 1);
}
@Test public void stereoToMonoAverages() { /* {1000,3000} → {2000} */ }
@Test public void downmixClamps() { /* all channels 30000 → 32767 */ }
@Test public void pack16AppliesGainLittleEndian() { /* {0x1234} gain 1 → {0x34,0x12}; gain 0.5 → 0x091A */ }
@Test public void pack24LeftJustified() { /* {0x1234} → {0x00,0x34,0x12} */ }
@Test public void packClampsAfterGain() { /* 30000 with gain 2 → 32767 */ }
@Test public void unpack24StereoToMono() { /* frames {L=0x1000,R=0x3000} at 3 bytes → 0x2000 */ }
@Test public void gainCurve() { assertEquals(0f, gainForLevel(0), 0); assertEquals(1f, gainForLevel(15), 0); assertEquals(0.36f, gainForLevel(9), 0.001); assertEquals(1f, gainForLevel(99), 0); }
// LinearResamplerTest
@Test public void identityWhenRatesEqual() { }
@Test public void upsample16kTo48kTriplesFrameCount() { /* 160 in → 480 out ±1 over one call; 1600 in over 10 calls → 4800 ±1 total */ }
@Test public void downsample48kTo441kKeepsLongTermRatio() { /* 48000 frames in 200 chunks of 240 → 44100 ±2 */ }
@Test public void continuousAcrossCalls() { /* a ramp split in two calls equals the same ramp in one call, sample by sample ±1 */ }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*PcmConverterTest*' --tests '*LinearResamplerTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement both classes.** No allocation per call: callers own the buffers.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: PCM channel/rate/width conversion and gain curve`.

---

### Task 4: Native isochronous engine

**Files:**
- Create: `app/src/main/jni/usb-audio/Android.mk`. It declares module `artemis-usbaudio` with `usb_audio.c`, `LOCAL_LDLIBS := -llog`, `-Wall -Werror`. The existing `jni/Android.mk` picks it up through `all-subdir-makefiles`; build it for both flavors.
- Create: `app/src/main/jni/usb-audio/usb_audio.c`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioNative.java`

**Interfaces:**
- **Produces** `final class UsbAudioNative`, whose static initializer runs `System.loadLibrary("artemis-usbaudio")`. Its static native methods:
  - `long open(int fd)`. Returns 0 on failure. Does not take ownership of the fd.
  - `int startPlayback(long h, int endpoint, int maxPacketBytes, int bInterval, int frameBytes, int sampleRate, int feedbackEndpoint)`. Returns 0, or `-errno`.
  - `int writePlayback(long h, byte[] data, int length)`.
    - Non-blocking.
    - Returns the bytes accepted (whole frames only; drops what doesn't fit).
    - Returns `-19` (ENODEV) once the device is gone.
  - `int startCapture(long h, int endpoint, int maxPacketBytes, int bInterval, int frameBytes, int sampleRate)`
  - `int readCapture(long h, byte[] buffer, int maxLength, int timeoutMs)`. Blocks up to `timeoutMs`. Returns bytes; 0 on timeout; negative when the device is gone or the handle closed.
  - `int getState(long h)`: 0 OK, −19 gone
  - `String getStats(long h)`: `"underruns=%d overruns=%d dropped=%d fbRate=%.2f queuedMs=%d"`
  - `void close(long h)`: stops both directions, discards and reaps all URBs, joins the thread, frees the handle.

**Native decisions** (constants at the top of `usb_audio.c`, so they can be tuned in Task 10):
- **Threading:** one reaper thread per handle, blocking in `USBDEVFS_REAPURB`. It resubmits completed URBs. To stop: set a flag, `USBDEVFS_DISCARDURB` every in-flight URB, then reap until none remain in flight.
- **Speed:** `USBDEVFS_GET_SPEED`; if the call fails, assume full speed.
  - Full speed: 1 packet/ms.
  - High speed: packets per ms = `8 >> (bInterval - 1)`, with `bInterval` clamped to 1..4.
- **URBs:** type `USBDEVFS_URB_TYPE_ISO`, flag `USBDEVFS_URB_ISO_ASAP`.
  - Playback: `PLAY_URBS 4` × `PLAY_MS_PER_URB 2`
  - Capture: `CAP_URBS 4` × `CAP_MS_PER_URB 4`
  - Feedback: 2 URBs × 1 packet
- **Packet sizing:**
  - A 16.16 fixed-point frames-per-packet accumulator. Nominal value = `sampleRate·65536 / (1000·packetsPerMs)`.
  - With a feedback endpoint, the nominal value is replaced by the latest feedback: full speed 10.14 in 3 bytes, `<< 2`; high speed 16.16 in 4 bytes. Feedback outside nominal ±10% is ignored.
  - Packet length is capped at `maxPacketBytes`.
- **Playback ring and drift:**
  - Ring holds 200 ms.
  - Draining starts once `PREFILL_MS 10` is queued.
  - When queued > `DRIFT_HIGH_MS 30`, drop one frame per packet until queued ≤ `DRIFT_TARGET_MS 20`.
  - On underrun, zero-fill (count it) and go back to prefill.
- **Capture ring:** holds 500 ms. When full, the oldest data is overwritten (count it as overruns). Readers wait on a condvar.
- **Errors:** `ENODEV`/`ESHUTDOWN` from submit or reap sets state GONE, wakes readers, and stops resubmitting.
- **Logging:** `__android_log_print` with tag `UsbAudio` on start and stop (with the parameters), and on the first error.

- [ ] **Step 1: Write `UsbAudioNative.java` and `usb_audio.c`** per the decisions above. JNI names: `Java_com_limelight_binding_audio_usb_UsbAudioNative_<method>`.
- [ ] **Step 2: Build.** Run `./gradlew :app:assembleNonRoot_gameDebug`. Expected: BUILD SUCCESSFUL with no warnings from `usb_audio.c`.
- [ ] **Step 3: Verify the library is packaged for every ABI.** Run `unzip -l app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-armeabi-v7a-debug.apk | grep artemis-usbaudio`, and the same for the other ABI APKs that exist. Expected: `lib/<abi>/libartemis-usbaudio.so` is listed.
- [ ] **Step 4: Confirm the unit tests still pass.** `./gradlew :app:testNonRoot_gameDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: native usbdevfs isochronous engine`.

On-device behaviour of this engine is verified in Task 10.

---

### Task 5: UAC control requests

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UacControl.java`
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UacControlTest.java`

**Interfaces:**
- **Consumes:** `UacDescriptors`, `AltSetting` (Task 1).
- **Produces** `interface UacControl.Transport { int control(int requestType, int request, int value, int index, byte[] buffer, int length, int timeoutMs); }` and `static Transport UacControl.transportFor(UsbDeviceConnection c)`. The latter wraps `controlTransfer`.
- **Produces** `UacControl(Transport t, UacDescriptors d)` with:
  - `boolean setSampleRate(AltSetting alt, int rate)`:
    - **UAC1:** only if `alt.hasSampleRateControl`. Request `0x22, 0x01, 0x0100, alt.endpointAddress`, 3-byte little-endian rate.
    - **UAC2:** `0x21, 0x01, 0x0100, (alt.clockSourceId << 8) | controlInterface`, 4-byte little-endian rate.
    - Returns true when no request is needed, or when the transfer returned the full length.
  - `void fillUac2Rates()`:
    - For each UAC2 alt, `GET_RANGE`: `0xA1, 0x02, 0x0100, (clock << 8) | controlInterface`.
    - First read 2 bytes to get `n`, then read `2 + 12n` bytes (n × min/max/res, 4-byte little-endian each).
    - Where min == max, the value goes into `sampleRates`; otherwise it sets `minRate`/`maxRate`.
    - On failure, the alt gets `sampleRates = {48000}` and the failure is logged.
  - `void unmuteAndSetUnityVolume()`. For every feature unit:
    - **Mute** (if present): `SET_CUR` to 0. UAC1: `0x21, 0x01, 0x0100, (unitId << 8) | controlInterface`, 1 byte. UAC2: same request with `bRequest 0x01`.
    - **Volume** (if present): read the maximum (UAC1 `GET_MAX`: `0xA1, 0x84, 0x0200, …`, 2 bytes; UAC2 `RANGE`: `0xA1, 0x02, 0x0200, …`, take the max). Then `SET_CUR` volume to `min(max, 0x0000)`, i.e. 0 dB, so headset loudness is controlled purely by Artemis's software gain.
    - Failures are logged and ignored.

- [ ] **Step 1: Write the failing tests.** Use a Mockito or recording fake `Transport`; assert the exact `requestType/request/value/index` and buffer bytes.

```java
@Test public void uac1SetsRateOnEndpoint() { /* ep 0x01, 48000 → (0x22,0x01,0x0100,0x0001,{0x80,0xBB,0x00}) */ }
@Test public void uac1WithoutRateControlSendsNothing() { }
@Test public void uac2SetsRateOnClock() { /* clock 0x29, ctrlIf 0 → index 0x2900, {0x80,0xBB,0x00,0x00} */ }
@Test public void uac2RangeFillsDiscreteAndContinuous() { /* n=2: [44100,44100,0],[48000,96000,1] → sampleRates={44100}, min 48000 max 96000 */ }
@Test public void uac2RangeFailureDefaultsTo48k() { }
@Test public void unmutesAndCapsVolumeAt0dB() { /* GET_MAX returns 0x0600 (+6 dB) → SET_CUR 0x0000; GET_MAX returns 0xF000 → SET_CUR 0xF000 */ }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*UacControlTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement `UacControl`.** Use a 1000 ms timeout for all transfers.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: UAC1/UAC2 sample-rate, range and feature-unit requests`.

---

### Task 6: Headset detection, permission and lifecycle

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioDetector.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioPermissionPolicy.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbHeadset.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioManager.java`
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioAttachActivity.java`
- Create: `app/src/main/res/xml/usb_audio_device_filter.xml`: `<resources><usb-device class="1" /></resources>`
- Modify: `app/src/main/AndroidManifest.xml`. Add the activity:
  - `android:enabled="false"`, `android:exported="true"`
  - theme `@android:style/Theme.Translucent.NoTitleBar`, `excludeFromRecents="true"`, `noHistory="true"`
  - intent filter `android.hardware.usb.action.USB_DEVICE_ATTACHED`
  - `<meta-data android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" android:resource="@xml/usb_audio_device_filter" />`
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UsbAudioDetectorTest.java`, `UsbAudioPermissionPolicyTest.java`, `UsbAudioManagerGainTest.java` (Robolectric)

**Why the disabled activity (builder's call):** Android shows the "Always open Artemis when … is connected" checkbox in the permission dialog only when the app has a matching `USB_DEVICE_ATTACHED` filter. Ticking it makes the permission permanent. The component is enabled (`PackageManager.setComponentEnabledSetting(..., COMPONENT_ENABLED_STATE_ENABLED, DONT_KILL_APP)`) only just before the first prompt for an Android-unsupported headset. That way users with supported headsets never see Artemis offered for their device. The activity itself only calls `finish()` in `onCreate`.

**Interfaces:**
- **Consumes:** Tasks 1, 2, 4 and 5.
- **Produces** `static boolean UsbAudioDetector.needsArtemisDriver(int sdkInt, int[][] interfaceClassSubclass, int[] androidAudioDeviceTypes)`. True iff:
  - `sdkInt >= 23`
  - some interface is class 1 / subclass 2
  - no type in `androidAudioDeviceTypes` is `AudioDeviceInfo.TYPE_USB_HEADSET` (22), `TYPE_USB_DEVICE` (11) or `TYPE_USB_ACCESSORY` (12)
- **Produces** `static boolean UsbAudioDetector.needsArtemisDriver(Context c, UsbDevice d)`. This adapter fills the arrays from `UsbDevice` and `AudioManager.getDevices(GET_DEVICES_ALL)`.
- **Produces** `final class UsbAudioPermissionPolicy`: `boolean shouldPrompt(String deviceKey)`, `void onDenied(String deviceKey)`, `void onAttached(String deviceKey)`. The last clears the denial. Device key: `String.format("%04x:%04x", vid, pid)`. State is in memory only, so a denial lasts until the headset is re-plugged or the app process restarts.
- **Produces** `final class UsbHeadset`:
  - `static UsbHeadset open(UsbManager m, UsbDevice d)`, nullable.
    1. Open the device.
    2. Log `toHex(getRawDescriptors())`.
    3. Parse.
    4. `claimInterface(intf, true)` for every class-1 interface.
    5. `UacControl.fillUac2Rates()` when UAC2.
    6. Select configs.
    7. `UsbAudioNative.open(fd)`.
    8. Log the chosen alts and rates.
    9. On any failure, release everything and return `null`.
  - `UacStreamConfig playbackConfig()` and `captureConfig()`, both nullable.
  - `boolean startPlayback()`: `setInterface` (alt), `setSampleRate`, `unmuteAndSetUnityVolume`, `UsbAudioNative.startPlayback`.
  - `int writePlayback(byte[] d, int len)`
  - `boolean startCapture()`
  - `int readCapture(byte[] b, int max, int timeoutMs)`
  - `boolean isAlive()`
  - `int vendorId()`, `productId()`
  - `String label()`: `UsbDevice.getProductName()`, falling back to `"USB headset"`.
  - `String deviceKey()`
  - `String stats()`
  - `void close()`: `UsbAudioNative.close`, `setInterface` to alt 0 for the streaming interfaces (ignore failures), release interfaces, `connection.close()`. Idempotent.
- **Produces** `final class UsbAudioManager`:
  - `static UsbAudioManager getInstance(Context c)` (application context)
  - `void start()`: idempotent. Registers a `RECEIVER_NOT_EXPORTED` receiver for `USB_DEVICE_ATTACHED`, `USB_DEVICE_DETACHED` and the private `com.limelight.USB_AUDIO_PERMISSION` action, using the same `PendingIntent` mutability and explicit-package pattern as `UsbDriverService.handleUsbDeviceState`. Catch `SecurityException` from `requestPermission`.
  - `UsbDevice findCandidate()`: the first attached device where `needsArtemisDriver` is true.
  - `void requestAccessIfNeeded()`: if there is a candidate, no permission, and `policy.shouldPrompt`, it enables the attach activity, then calls `requestPermission`.
  - `boolean isHeadsetReady()`: a candidate exists and `hasPermission` is true. Volatile-cached; refreshed on every broadcast.
  - `UsbHeadset acquire()`, nullable. Reference-counted: one shared `UsbHeadset` for playback and capture.
  - `void release(UsbHeadset h)`: closes at refcount 0.
  - `UsbHeadset activeHeadset()`: nullable; the currently acquired headset.
  - `void addListener(Listener l)` / `removeListener`, with `interface Listener { void onHeadsetAvailabilityChanged(boolean available); }`, called on the main thread when `isHeadsetReady()` flips.
  - On detach of the active device: mark it gone (the native layer reports ENODEV anyway) and notify.
  - `int getGainLevel()`, `void setGainLevel(int level)`: per device key, persisted in default `SharedPreferences` under `usb_audio_gain_<vid>_<pid>` (lowercase hex, 4 digits), default `PcmConverter.DEFAULT_GAIN_LEVEL`, clamped to 0..15.
  - `float currentGain()`: volatile, read from the audio thread.
  - Also implements `UsbAudioRenderer.PlaybackProvider` (Task 7) and `UsbHeadsetPcmSource.Provider` (Task 8). Write those methods there.

- [ ] **Step 1: Write the failing tests.**

```java
// UsbAudioDetectorTest
static final int[][] G435 = {{3,0},{1,1},{1,2},{1,2}};
@Test public void unsupportedHeadsetNeedsDriver() { assertTrue(needsArtemisDriver(34, G435, new int[]{9 /*HDMI*/, 2})); }
@Test public void androidSupportedHeadsetIsLeftAlone() { assertFalse(needsArtemisDriver(34, G435, new int[]{22})); assertFalse(needsArtemisDriver(34, G435, new int[]{11})); }
@Test public void controllerIsNotAHeadset() { assertFalse(needsArtemisDriver(34, new int[][]{{255,93}}, new int[0])); }
@Test public void inactiveBelowApi23() { assertFalse(needsArtemisDriver(22, G435, new int[0])); }
// UsbAudioPermissionPolicyTest
@Test public void deniedHeadsetIsNotPromptedAgainUntilReattached() {
    p.onDenied("046d:0adf"); assertFalse(p.shouldPrompt("046d:0adf")); assertTrue(p.shouldPrompt("1234:5678"));
    p.onAttached("046d:0adf"); assertTrue(p.shouldPrompt("046d:0adf"));
}
// UsbAudioManagerGainTest (Robolectric)
@Test public void gainDefaultsToModerateAndPersistsPerHeadset() { /* new key → 9; set 12 → new manager instance reads 12; other key still 9; set 40 → 15 */ }
```

  For the gain test, expose a package-private `int getGainLevel(String deviceKey)` / `setGainLevel(String deviceKey, int level)` so no `UsbDevice` is needed.
- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*UsbAudioDetectorTest*' --tests '*UsbAudioPermissionPolicyTest*' --tests '*UsbAudioManagerGainTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement the detector, the policy, `UsbHeadset`, `UsbAudioManager`, the activity, the filter XML and the manifest entry.**
- [ ] **Step 4: Run the full unit test suite.** `./gradlew :app:testNonRoot_gameDebugUnitTest`. Expected: PASS, including the existing `StartupTest`/`LayoutInflationTest`. The manifest change must not break them.
- [ ] **Step 5: Commit.** Message: `usb-audio: detection, one-time permission, headset lifecycle, gain store`.

---

### Task 7: Game audio renderer with live switching

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbAudioRenderer.java`
- Modify: `app/src/main/java/com/limelight/Game.java:879`. Build `new UsbAudioRenderer(UsbAudioManager.getInstance(Game.this), new AndroidAudioRenderer(Game.this, prefConfig.playHostAudio), SystemClock::elapsedRealtime)`. Just before `conn.start(...)`, call `UsbAudioManager.getInstance(Game.this).start()` and `requestAccessIfNeeded()`.
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UsbAudioRendererTest.java`. Robolectric with `@Config(shadows = ShadowMoonBridge.class)`, following `NvConnectionMicrophoneTest`, because `MoonBridge.AudioConfiguration` lives in `MoonBridge`.

**Interfaces:**
- **Consumes:** `PcmConverter`, `LinearResampler` (Task 3); `UacStreamConfig` (Task 2); the existing `com.limelight.nvstream.av.audio.AudioRenderer`.
- **Produces** `interface UsbAudioRenderer.PlaybackOutput`: `UacStreamConfig config(); boolean start(); int write(byte[] data, int length); boolean isAlive(); void close();`. In production, `UsbAudioManager` returns an adapter over an acquired `UsbHeadset` whose `close()` calls `release`.
- **Produces** `interface UsbAudioRenderer.PlaybackProvider`: `boolean isHeadsetReady(); PlaybackOutput openPlayback() /* nullable */; float currentGain();`.
- **Produces** `UsbAudioRenderer(PlaybackProvider p, AudioRenderer fallback, LongSupplier clockMs) implements AudioRenderer`.

**Behaviour (decided here):**
- **`setup`:** stores the config. If `isHeadsetReady()` and `openPlayback().start()` succeeds, the renderer is on USB and the fallback is not set up. Otherwise it calls `fallback.setup(...)` and returns its result.
- **`playDecodedAudio` on USB:**
  1. `toDeviceChannels`
  2. resample 48000 → `config.sampleRate`
  3. `pack` with `currentGain()`
  4. `write`
- **Switching to the fallback:** a negative `write` or `!isAlive()` closes the output. The renderer then calls `fallback.setup(storedConfig…)` and `fallback.start()` and plays the same frame there. All of this happens on the calling (audio) thread, without exceptions.
- **Switching back to USB:** while on the fallback, at most every 500 ms (by `clockMs`), the renderer checks `isHeadsetReady()`. If it is ready and `openPlayback().start()` succeeds, it calls `fallback.stop()` and `fallback.cleanup()` and moves to USB. This covers both first-use permission granted mid-stream and re-plugging.
- **`start`/`stop`/`cleanup`** go to whichever path is active. `cleanup` also closes the USB output.
- **Buffers** are allocated in `setup` for 10 ms frames at up to 8 channels and the device rate. No allocation per frame.

- [ ] **Step 1: Write the failing tests** with fakes for the provider, the output (records bytes; scriptable `write` result and `isAlive`) and the fallback (records calls).

```java
@Test public void headsetReadyUsesUsbAndNeverTouchesFallback() { /* setup stereo 48k/240 → 0; play 240 frames → fake got 960 bytes; fallback.setupCalls==0 */ }
@Test public void noHeadsetUsesFallback() { /* isHeadsetReady false → fallback.setup called once, plays go to fallback */ }
@Test public void unplugMidStreamFallsBackToAndroid() { /* write returns -19 → fallback.setup+start called once, that same frame reaches fallback, output.close called, no exception */ }
@Test public void headsetAppearingMidStreamTakesOverAfterInterval() { /* not ready → fallback; ready + clock +400ms → still fallback; +600ms → fallback.stop+cleanup, next frame to USB */ }
@Test public void surroundStreamIsDownmixed() { /* 6ch config, 240 frames → output receives 240*2*2 bytes */ }
@Test public void gainIsAppliedFromProvider() { /* currentGain 0.5, sample 0x1000 → bytes encode 0x0800 */ }
@Test public void resamplesTo441kDevice() { /* device 44100 → over 100 frames of 240, total bytes = 44100/48000*24000*4 ±8 */ }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*UsbAudioRendererTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement `UsbAudioRenderer`, add the `PlaybackProvider` methods to `UsbAudioManager`, and wire `Game`.**
- [ ] **Step 4: Run the full suite.** `./gradlew :app:testNonRoot_gameDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: renderer with live headset/Android switching`.

---

### Task 8: Headset microphone

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbHeadsetPcmSource.java`
- Modify: `app/src/main/java/com/limelight/binding/audio/MicrophoneCaptureManager.java`. Changes:
  - Introduce `PcmSource`; move the `AudioRecord` path behind it (`AudioRecordPcmSource`, a private nested class).
  - `runCaptureLoop` reads from a `PcmSource`. Level metering and `queueMicrophonePcm` are unchanged.
- Modify: `app/src/main/java/com/limelight/preferences/StreamSettings.java` (`populateMicrophoneDevicePreference`, about line 1039). Add an entry when `UsbAudioManager.getInstance(ctx).findCandidate() != null`.
- Modify: `app/src/main/java/com/limelight/Game.java`.
  - In `onCreate`, register a `UsbAudioManager.Listener`.
  - When availability flips while `connected && prefConfig.enableMicrophone` and `microphoneDeviceId` is 0 or `USB_HEADSET_DEVICE_ID`, run `stopMicrophoneCapture(); startMicrophoneCapture();`.
  - Unregister in `onDestroy`.
- Modify: `app/src/main/res/values/strings.xml`. Add `<string name="microphone_device_usb_headset">%1$s (USB headset)</string>`.
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UsbHeadsetPcmSourceTest.java`, `app/src/test/java/com/limelight/binding/audio/MicrophoneSourceSelectionTest.java`

**Interfaces:**
- **Consumes:** `PcmConverter.unpackToMono`, `LinearResampler` (Task 3); `UsbAudioManager` (Task 6).
- **Produces** `interface MicrophoneCaptureManager.PcmSource { int read(short[] buffer); void close(); String label(); }`. `read` fills 48 kHz mono samples. It blocks at most about 100 ms and returns the sample count; 0 means "nothing yet", negative means "source ended".
- **Produces** `static final int MicrophoneCaptureManager.USB_HEADSET_DEVICE_ID = -2`.
- **Produces** `static boolean MicrophoneCaptureManager.shouldUseUsbHeadset(int preferredDeviceId, boolean headsetReady)`. True iff `headsetReady && (preferredDeviceId == 0 || preferredDeviceId == USB_HEADSET_DEVICE_ID)`.
- **Produces** `interface UsbHeadsetPcmSource.CaptureInput { UacStreamConfig config(); boolean start(); int read(byte[] buf, int max, int timeoutMs); void close(); }`.
- **Produces** `interface UsbHeadsetPcmSource.Provider { boolean isHeadsetReady(); CaptureInput openCapture() /* nullable */; String headsetLabel(); }`. Implemented by `UsbAudioManager` on top of `acquire()`, `startCapture()`, `readCapture()` and `release()`.
- **Produces** `UsbHeadsetPcmSource(CaptureInput in) implements MicrophoneCaptureManager.PcmSource`. Reads with a 100 ms timeout, then `unpackToMono`, then resamples to 48000.

**Behaviour:**
- `startCapture` picks the USB source when `shouldUseUsbHeadset(...)` is true and `openCapture()` returns non-null with a successful `start()`. Otherwise it uses the existing `AudioRecord` path, unchanged.
- If the USB source returns negative mid-stream, the loop closes it, logs at info level, and tries once to continue on the `AudioRecord` path with the same `preferredDeviceId` rules minus USB. If that also fails, capture stops quietly; the stream is unaffected.
- The settings preview (`startPreview`) follows the same rule but never prompts for permission. It uses USB only if `isHeadsetReady()`.
- **Picker entry:** label `getString(R.string.microphone_device_usb_headset, headsetLabel)`, value `"-2"`. It is inserted right after "Default", and it counts as present for the `hasSelectedDevice` check.

- [ ] **Step 1: Write the failing tests.**

```java
// MicrophoneSourceSelectionTest
@Test public void defaultUsesReadyHeadset() { assertTrue(shouldUseUsbHeadset(0, true)); }
@Test public void explicitOtherMicWins() { assertFalse(shouldUseUsbHeadset(7, true)); }
@Test public void explicitHeadsetPick() { assertTrue(shouldUseUsbHeadset(-2, true)); assertFalse(shouldUseUsbHeadset(-2, false)); }
@Test public void noHeadsetNoUsb() { assertFalse(shouldUseUsbHeadset(0, false)); }
// UsbHeadsetPcmSourceTest (fake CaptureInput)
@Test public void converts16kMonoTo48k() { /* input 16 ms of 16k mono (256 samples) → read() returns 768 ±1 samples */ }
@Test public void converts48kStereo24ToMono() { /* L=0x1000 R=0x3000 frames → samples 0x2000 */ }
@Test public void timeoutReturnsZero() { /* input read returns 0 → read() returns 0 */ }
@Test public void usbSourceReportsEndWhenDeviceGone() { /* input read returns -19 → read() negative, no exception */ }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*MicrophoneSourceSelectionTest*' --tests '*UsbHeadsetPcmSourceTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement the source, the refactor, the `UsbAudioManager` provider methods, the picker entry, the `Game` listener and the string.**
- [ ] **Step 4: Run the full suite.** `./gradlew :app:testNonRoot_gameDebugUnitTest`. Expected: PASS, including `NvConnectionMicrophoneTest`.
- [ ] **Step 5: Commit.** Message: `usb-audio: headset microphone source and picker entry`.

---

### Task 9: Headset volume wheel

**Files:**
- Create: `app/src/main/java/com/limelight/binding/audio/usb/UsbHeadsetVolumeKeys.java`
- Modify: `app/src/main/java/com/limelight/Game.java`. At the start of `handleKeyDown` (line 2034) and `handleKeyUp` (line 2125), before the virtual-key check, add one call each:
  - Key down: `UsbHeadsetVolumeKeys.handle(event, UsbAudioManager.getInstance(this), this::displayTransientMessage, true)` returning true means consumed.
  - Key up: the same call with `false`.
- Modify: `app/src/main/res/values/strings.xml`. Add `<string name="usb_headset_volume">Headset volume %1$d%%</string>`.
- Test: `app/src/test/java/com/limelight/binding/audio/usb/UsbHeadsetVolumeKeysTest.java`

**Interfaces:**
- **Consumes:** `UsbAudioManager.activeHeadset()`, `getGainLevel()` / `setGainLevel(int)`; `PcmConverter.MAX_GAIN_LEVEL` (Tasks 3 and 6).
- **Produces** `static boolean UsbHeadsetVolumeKeys.matches(int keyCode, int eventVendorId, int eventProductId, int headsetVendorId, int headsetProductId)`. True iff `keyCode` is `KEYCODE_VOLUME_UP` or `KEYCODE_VOLUME_DOWN` and the vendor and product IDs are equal.
- **Produces** `static int UsbHeadsetVolumeKeys.nextLevel(int level, int keyCode)`: ±1, clamped to 0..15.
- **Produces** `static boolean handle(KeyEvent e, UsbAudioManager m, Consumer<String> toast, boolean down)`.
  - Returns false when there is no active headset or the event's `InputDevice` doesn't match.
  - On key down (including repeats): sets the next level and toasts the percentage `round(level * 100 / 15)`.
  - On key up: consumes only.

- [ ] **Step 1: Write the failing tests.**

```java
@Test public void headsetWheelMatches() { assertTrue(matches(KEYCODE_VOLUME_UP, 0x046d, 0x0adf, 0x046d, 0x0adf)); }
@Test public void tvRemoteVolumeIsNotConsumed() { assertFalse(matches(KEYCODE_VOLUME_UP, 0x0957, 0x0001, 0x046d, 0x0adf)); }
@Test public void otherKeysFromHeadsetIgnored() { assertFalse(matches(KEYCODE_MEDIA_PLAY_PAUSE, 0x046d, 0x0adf, 0x046d, 0x0adf)); }
@Test public void levelClamps() { assertEquals(15, nextLevel(15, KEYCODE_VOLUME_UP)); assertEquals(0, nextLevel(0, KEYCODE_VOLUME_DOWN)); assertEquals(8, nextLevel(9, KEYCODE_VOLUME_DOWN)); }
```

- [ ] **Step 2: Run the tests to verify they fail.** `--tests '*UsbHeadsetVolumeKeysTest*'`. Expected: compilation failure.
- [ ] **Step 3: Implement the class, wire `Game`, add the string.**
- [ ] **Step 4: Run the full suite.** Expected: PASS.
- [ ] **Step 5: Commit.** Message: `usb-audio: headset volume wheel controls headset gain`.

---

### Task 10: On-device verification on the Chromecast (definition of done)

**Files:**
- Modify only if tuning is needed: `app/src/main/jni/usb-audio/usb_audio.c` (constants), `app/src/test/resources/usb/` (the real dump, if Task 1 used the synthetic fixture).
- Modify: `UsbAudioRenderer`. While on USB, log `headset.stats()` every 10 s at info level.

This task needs the user: plugging in, listening, speaking, turning the wheel. Ask before each physical step, and record each result.

- [ ] **Step 1: Build and install.** Run `./gradlew :app:assembleNonRoot_gameDebug`, then `adb -s 192.168.178.35:<port> install -r app/build/outputs/apk/nonRoot_game/debug/app-nonRoot_game-armeabi-v7a-debug.apk`. Expected: `Success`.
- [ ] **Step 2: Start the log.** `adb logcat -c` then `adb logcat -s UsbAudio:* moonlight:*`, running in the background.
- [ ] **Step 3: First use.**
  - With the G435 plugged in, open Artemis and start a stream.
  - Expected: Android's permission dialog shows an "Always open…" checkbox. The user ticks it and allows.
  - Log shows the descriptors hex, the chosen output/input alt and rate, and `startPlayback` = 0.
  - Game audio is audible in the G435, and the TV is silent.
- [ ] **Step 4: Microphone.** With "Enable microphone" on, the user speaks. Expected: the Windows "Steam Streaming Microphone" level meter moves on the PC.
- [ ] **Step 5: Volume wheel.** Expected: the headset volume changes, a toast shows "Headset volume N%", and the TV volume is unchanged. The TV remote's volume still changes the TV.
- [ ] **Step 6: Quality.** Listen for one minute. Expected: no crackling. The 10-second stats show `underruns` not growing steadily, and latency similar to TV audio. If underruns climb, raise `PREFILL_MS`/`DRIFT_TARGET_MS` by 5 ms, rebuild, and repeat.
- [ ] **Step 7: Unplug mid-stream.** Expected: the stream continues and game audio comes from the TV within about 1 s, with no crash in logcat. Re-plug: audio returns to the headset within about 1 s, with no permission dialog.
- [ ] **Step 8: One-time permission.** Force-stop Artemis, reopen it, start a stream. Expected: no dialog; audio in the headset.
- [ ] **Step 9: Fix the fixture.** If the real dump differs from the Task 1 fixture, replace the fixture, update the `parsesG435` assertions, and run the suite. Expected: PASS.
- [ ] **Step 10: Commit any tuning and fixture changes.** Message: `usb-audio: tune buffers and record G435 descriptors from device`.
