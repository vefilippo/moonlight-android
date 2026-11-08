# Phone Keyboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a paired phone's browser type text into the streamed PC through Pheme on the TV.

**Architecture:** A small HTTP server inside Pheme's process serves a single-page keyboard
and a JSON API. Requests are authenticated by a per-phone secret. Text is split into key
presses (letters, digits, space) and Unicode runs, then delivered in order through a hub to
whichever stream is active. The server runs only while at least one phone is paired or a
pairing QR is on screen.

**Tech Stack:** Java (Android app module `app/`), NanoHTTPD 2.3.1, ZXing core 3.5.3 (QR),
`org.json` (built-in) for storage and the API, JUnit 4 + Robolectric 4.16 + Mockito.

**Spec:** `docs/superpowers/specs/2026-11-08-phone-keyboard-design.md`

## Global Constraints

- Package for all new classes: `com.limelight.phonekeyboard`. Tests mirror it under `app/src/test/java/com/limelight/phonekeyboard/`.
- Fixed server port: `PhoneKeyboardServer.PORT = 47900`.
- Pairing code lifetime: `PairingCodes.TTL_MS = 300_000` (5 min); one-time use; all cancelled when the pairing dialog closes.
- Phone secret: 32 bytes from `SecureRandom`, base64url without padding. Compare with `MessageDigest.isEqual`.
- Max text per send: 4000 chars (`PhoneKeyboardApi.MAX_TEXT_CHARS`); longer, and the request gets 413.
- Text content is **never logged**. Log lines may contain only counts.
- Newlines (`\r\n`, `\r`, `\n`) in sent text become a single space each.
- Key presses only for `a-z`, `A-Z` (with Shift), `0-9`, space. Everything else goes as Unicode text. Order is preserved.
- No external requests from the page (no CDN, inline CSS/JS only).
- Storage uses `org.json`, not Gson: debug builds are minified, and Gson field names would change.
- UI strings in `values/strings.xml` (English) and `values-it/strings.xml` (Italian).
- Commit messages contain no tool attribution trailers.
- Full suite stays green: `./gradlew :app:testNonRoot_gameDebugUnitTest`.

## Review Focus

- **Emoji or accented text mixed with letters** must arrive in the original order, with surrogate pairs never split. Test in Task 1 (`split_keepsSurrogatePairsInOneTextRun`).
- **Long pasted text with multibyte characters** (over 512 UTF-8 bytes) must be chunked only on code-point boundaries. Test in Task 1 (`utf8Chunks_neverSplitsCodePoints`).
- **Stream ends mid-send:** remaining actions are dropped quietly, with no crash or late typing into the next stream. Test in Task 3 (`hub_dropsRemainingActionsWhenSinkDetachedMidSend`).
- **Two phones sending at once:** each send's actions arrive contiguously, never interleaved. Test in Task 3 (`hub_doesNotInterleaveTwoSends`).
- **A removed phone keeps its page open:** every API call gets 401 and the page shows "not paired". Test in Task 4 (`api_rejectsRemovedPhone`).

---

### Task 1: Text splitting and special keys

**Files:**
- Create: `app/src/main/java/com/limelight/phonekeyboard/TypingAction.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/SpecialKey.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/TextSplitter.java`
- Modify: `app/src/main/java/com/limelight/Game.java` (`enqueueCommitText`, ~line 4464) to use `TextSplitter.utf8Chunks`
- Test: `app/src/test/java/com/limelight/phonekeyboard/TextSplitterTest.java`

**Interfaces:**
- Produces:
  - `final class TypingAction` with `enum Kind { KEY, TEXT }`, `static TypingAction key(short vk, boolean shift)`, `static TypingAction text(String s)`, getters `kind()`, `vk()`, `shift()`, `text()`, value `equals/hashCode/toString`.
  - `enum SpecialKey { ENTER, BACKSPACE, TAB, ESCAPE, UP, DOWN, LEFT, RIGHT }` with `short vk()` (VK_RETURN, VK_BACK_SPACE, VK_TAB, VK_ESCAPE, VK_UP, VK_DOWN, VK_LEFT, VK_RIGHT from `KeyboardTranslator`) and `static SpecialKey fromWireName(String)`. Wire names are `enter, backspace, tab, esc, up, down, left, right`, unknown or null returns `null`.
  - `final class TextSplitter` with `static List<TypingAction> split(String input)` and `static List<String> utf8Chunks(String text, int maxBytes)`.

- [ ] **Step 1: Write the failing tests** (plain JUnit, no Robolectric)

```java
@Test public void split_lettersDigitsSpaceAreKeys() {
    assertEquals(Arrays.asList(
        TypingAction.key((short) KeyboardTranslator.VK_A, false),
        TypingAction.key((short) (KeyboardTranslator.VK_A + 1), true),   // 'B'
        TypingAction.key((short) (KeyboardTranslator.VK_0 + 7), false),  // '7'
        TypingAction.key((short) KeyboardTranslator.VK_SPACE, false)),
        TextSplitter.split("aB7 "));
}
@Test public void split_symbolsAndAccentsBecomeOneTextRunInOrder() {
    assertEquals(Arrays.asList(
        TypingAction.key((short) (KeyboardTranslator.VK_A + 15), false), // 'p'
        TypingAction.text("@è!"),
        TypingAction.key((short) (KeyboardTranslator.VK_0 + 1), false)), // '1'
        TextSplitter.split("p@è!1"));
}
@Test public void split_keepsSurrogatePairsInOneTextRun() {
    assertEquals(Arrays.asList(TypingAction.text("😀€")), TextSplitter.split("😀€"));
}
@Test public void split_newlinesBecomeSpaces() {
    assertEquals(TextSplitter.split("a b c d"), TextSplitter.split("a\r\nb\nc\rd"));
}
@Test public void split_emptyAndNullGiveEmptyList() {
    assertTrue(TextSplitter.split("").isEmpty());
    assertTrue(TextSplitter.split(null).isEmpty());
}
@Test public void utf8Chunks_neverSplitsCodePoints() {
    String s = "è".repeat(300);                  // 600 bytes
    List<String> chunks = TextSplitter.utf8Chunks(s, 512);
    assertEquals(s, String.join("", chunks));
    for (String c : chunks) assertTrue(c.getBytes(StandardCharsets.UTF_8).length <= 512);
    assertEquals(2, chunks.size());
}
@Test public void specialKey_wireNames() {
    assertEquals(SpecialKey.ESCAPE, SpecialKey.fromWireName("esc"));
    assertEquals((short) KeyboardTranslator.VK_RETURN, SpecialKey.ENTER.vk());
    assertNull(SpecialKey.fromWireName("f13"));
    assertNull(SpecialKey.fromWireName(null));
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.phonekeyboard.TextSplitterTest'`
Expected: compilation FAIL (classes don't exist).

- [ ] **Step 3: Implement the three classes.** `split` walks code points (`codePointAt` / `Character.charCount`), merges consecutive non-key code points into one TEXT action, and normalizes newlines first (`\r\n` before `\r` and `\n`). `utf8Chunks` holds the code-point-boundary logic now inline in `Game.enqueueCommitText`.

- [ ] **Step 4: Change `Game.enqueueCommitText`** to `commitTextQueue.addAll(TextSplitter.utf8Chunks(text, UTF8_CHUNK_SIZE))`, keeping its null/empty guard and the "kick off flushing" logic.

- [ ] **Step 5: Run the new tests and the full suite**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/TextSplitterTest.java app/src/main/java/com/limelight/Game.java
git commit -m "phone-keyboard: split text into key presses and Unicode runs"
```

---

### Task 2: Paired phones and pairing codes

**Files:**
- Create: `app/src/main/java/com/limelight/phonekeyboard/PairedPhone.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PairedPhoneStore.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PairingCodes.java`
- Test: `app/src/test/java/com/limelight/phonekeyboard/PairedPhoneStoreTest.java` (Robolectric, `@Config(sdk = {33})`)
- Test: `app/src/test/java/com/limelight/phonekeyboard/PairingCodesTest.java` (plain JUnit)

**Interfaces:**
- Produces:
  - `final class PairedPhone` with public final fields `String id, label, secret; long createdAtMs, lastUsedMs`.
  - `final class PairedPhoneStore(SharedPreferences prefs, LongSupplier clock)` with `PairedPhone addPhone(String label)`, `PairedPhone findBySecret(String secret)` (null if missing or null), `void markUsed(String id)`, `boolean remove(String id)`, `List<PairedPhone> list()` (oldest first), `boolean isEmpty()`. Production prefs come from `context.getSharedPreferences("phone_keyboard", MODE_PRIVATE)` under key `"phones"` (JSON array via `org.json`).
  - `final class PairingCodes(LongSupplier clock)` with `TTL_MS = 300_000`, `String issue()`, `boolean consume(String code)` (true once, if unexpired), `void cancelAll()`, `boolean hasActive()`.

- [ ] **Step 1: Write the failing tests**

```java
// PairedPhoneStoreTest
@Test public void addedPhoneIsFoundBySecretAndSurvivesReload() {
    PairedPhone p = store.addPhone("iPhone");
    assertEquals(43, p.secret.length());
    assertEquals(p.id, new PairedPhoneStore(prefs, () -> 0L).findBySecret(p.secret).id);
}
@Test public void unknownOrNullSecretIsNotFound() {
    store.addPhone("x");
    assertNull(store.findBySecret("nope"));
    assertNull(store.findBySecret(null));
}
@Test public void removedPhoneIsGone() {
    PairedPhone p = store.addPhone("x");
    assertTrue(store.remove(p.id));
    assertNull(store.findBySecret(p.secret));
    assertTrue(store.isEmpty());
}
@Test public void markUsedUpdatesLastUsed() {
    now = 1000; PairedPhone p = store.addPhone("x");
    now = 5000; store.markUsed(p.id);
    assertEquals(5000, store.list().get(0).lastUsedMs);
}
// PairingCodesTest
@Test public void codeIsOneTime() { String c = codes.issue(); assertTrue(codes.consume(c)); assertFalse(codes.consume(c)); }
@Test public void codeExpiresAfterTtl() { String c = codes.issue(); now += PairingCodes.TTL_MS + 1; assertFalse(codes.consume(c)); assertFalse(codes.hasActive()); }
@Test public void cancelAllInvalidates() { String c = codes.issue(); codes.cancelAll(); assertFalse(codes.consume(c)); }
```

(`now` is a mutable long field and the clock is `() -> now`. `prefs` comes from `ApplicationProvider.getApplicationContext().getSharedPreferences("test_phones", 0)`, cleared in `@Before`.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.phonekeyboard.PairedPhoneStoreTest' --tests 'com.limelight.phonekeyboard.PairingCodesTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement the three classes.** Secrets and codes use `SecureRandom` with base64url (`Base64.getUrlEncoder().withoutPadding()`, 32 bytes for secrets, 16 for codes). Look-ups use `MessageDigest.isEqual` on UTF-8 bytes. The store and codes are `synchronized`, because the server thread and the UI both call them.

- [ ] **Step 4: Run tests to verify they pass** (same command, expected PASS).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/
git commit -m "phone-keyboard: persistent paired phones and one-time pairing codes"
```

---

### Task 3: Typing hub and connection sink

**Files:**
- Create: `app/src/main/java/com/limelight/phonekeyboard/TypingSink.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PhoneKeyboardHub.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/ConnectionTypingSink.java`
- Modify: `app/src/main/java/com/limelight/Game.java`: attach in `connectionStarted()` (~line 3816, next to `connected = true`), detach in `stopConnection()` (~line 3520)
- Test: `app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardHubTest.java` (plain JUnit)

**Interfaces:**
- Consumes: `TypingAction`, `SpecialKey`, `TextSplitter.utf8Chunks` (Task 1).
- Produces:
  - `interface TypingSink { void sendKey(short vk, boolean shift); void sendText(String text); }`. `sendKey` means a full press: down, then up.
  - `final class PhoneKeyboardHub(Executor executor)` plus `static PhoneKeyboardHub get()`, a process singleton on a single-thread executor named `PhoneKeyboardTyping`. Methods: `void attach(TypingSink)`, `void detach(TypingSink)` (no-op if another sink is attached), `boolean isStreamActive()`, `boolean type(List<TypingAction>)` and `boolean press(SpecialKey)`. Both return false when no sink is attached; otherwise they enqueue **one task per call**.
  - `final class ConnectionTypingSink(NvConnection conn)` implementing `TypingSink`. `sendKey`: if shift, `VK_LSHIFT` down, then the key down/up with `KeyboardPacket.MODIFIER_SHIFT`, then `VK_LSHIFT` up. Otherwise key down/up with modifier 0, flags 0. Sleep `KEY_PACING_MS = 8` after each press. `sendText` sends `utf8Chunks(text, 512)` with `TEXT_CHUNK_PACING_MS = 15` between chunks.

- [ ] **Step 1: Write the failing tests** with a recording fake sink and a direct executor (`Runnable::run`) or a controllable queue executor:

```java
@Test public void typeFailsWithoutSink() { assertFalse(hub.type(TextSplitter.split("a"))); }
@Test public void typeDeliversActionsInOrder() {
    hub.attach(sink); hub.type(TextSplitter.split("a!"));
    assertEquals(Arrays.asList("key:65:false", "text:!"), sink.events);
}
@Test public void hub_doesNotInterleaveTwoSends() {
    hub = new PhoneKeyboardHub(queued); hub.attach(sink);
    hub.type(TextSplitter.split("ab")); hub.type(TextSplitter.split("cd"));
    queued.runAll();
    assertEquals(Arrays.asList("key:65:false","key:66:false","key:67:false","key:68:false"), sink.events);
}
@Test public void hub_dropsRemainingActionsWhenSinkDetachedMidSend() {
    hub = new PhoneKeyboardHub(Runnable::run);
    RecordingSink detaching = new RecordingSink();
    detaching.afterFirstEvent = () -> hub.detach(detaching);   // stream ends after the first key
    hub.attach(detaching); hub.type(TextSplitter.split("abc"));
    assertEquals(1, detaching.events.size());
}
@Test public void detachIgnoresOtherSink() { hub.attach(sink); hub.detach(new RecordingSink()); assertTrue(hub.isStreamActive()); }
```

(The mid-send test needs the hub to re-check its current sink before **each** action, and to stop when that sink has changed. `RecordingSink` is a test helper: it records `"key:<vk>:<shift>"` / `"text:<s>"` into `events`, with an optional `Runnable afterFirstEvent`.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.phonekeyboard.PhoneKeyboardHubTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `TypingSink`, `PhoneKeyboardHub`, `ConnectionTypingSink`.** The hub keeps the sink in a `volatile` field. A queued task captures the sink present at enqueue time and stops as soon as `this.sink != captured`.

- [ ] **Step 4: Wire `Game`.** Add a field `private ConnectionTypingSink phoneKeyboardSink;`. In `connectionStarted()`'s UI runnable, after `connected = true;`, create it from `conn` and call `PhoneKeyboardHub.get().attach(...)`. In `stopConnection()`, before `connecting = connected = false;`, detach it and null the field.

- [ ] **Step 5: Run the new tests and the full suite** (expected BUILD SUCCESSFUL).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardHubTest.java app/src/main/java/com/limelight/Game.java
git commit -m "phone-keyboard: ordered typing hub fed into the active stream"
```

---

### Task 4: Request handling (API)

**Files:**
- Create: `app/src/main/java/com/limelight/phonekeyboard/ApiResponse.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PhoneKeyboardApi.java`
- Test: `app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardApiTest.java` (Robolectric, for `org.json` and prefs)

**Interfaces:**
- Consumes: `PairedPhoneStore`, `PairingCodes` (Task 2), `PhoneKeyboardHub`, `SpecialKey`, `TextSplitter` (Tasks 1, 3).
- Produces:
  - `final class ApiResponse` with public final fields `int status; String mimeType; String body; Map<String,String> headers`.
  - `final class PhoneKeyboardApi(PairedPhoneStore store, PairingCodes codes, PhoneKeyboardHub hub, Supplier<String> pageHtml, Runnable onPaired)` with `MAX_TEXT_CHARS = 4000` and `ApiResponse handle(String method, String path, Map<String,String> headers, String body)`. Header names arrive lower-cased (NanoHTTPD does this). The API key header is `x-pheme-key`.

Routes (exact statuses):

| Request | Result |
|---|---|
| `GET /pair/{code}` with valid code | adds phone labelled from `user-agent` (`iPhone`, `iPad`, `Android phone`, else `Phone`), runs `onPaired`, **302** `Location: /k/{secret}` |
| `GET /pair/{code}` with invalid, used or expired code | **410** text/html "This pairing link has expired. Show the QR code on the TV again." |
| `GET /k/{secret}` with valid secret | **200** text/html from `pageHtml` |
| `GET /k/{secret}` with invalid secret | **403** text/html "This phone isn't paired (or was removed). Pair it again from Pheme on the TV." |
| `GET /api/status` | **200** `{"state":"ready"}` or `{"state":"waiting"}`, or **401** with a bad key |
| `POST /api/text` `{"text":"…"}` | **401** bad key, **400** bad JSON or missing `text`, **413** over 4000 chars, **409** `{"error":"waiting"}` with no stream, else **200** `{"ok":true}`, and `markUsed` |
| `POST /api/key` `{"key":"enter"}` | **401**, **400** unknown key, **409**, else **200** `{"ok":true}`, and `markUsed` |
| anything else | **404** |

API responses are `application/json`. Every response sets `Cache-Control: no-store`.

- [ ] **Step 1: Write the failing tests** (fake hub sink via `hub.attach`, direct executor):

```java
@Test public void pairRedirectsToPhonePageOnce() {
    String code = codes.issue();
    ApiResponse r = api.handle("GET", "/pair/" + code, ua("…iPhone…"), "");
    assertEquals(302, r.status);
    String secret = r.headers.get("Location").substring("/k/".length());
    assertEquals("iPhone", store.findBySecret(secret).label);
    assertTrue(paired[0]);
    assertEquals(410, api.handle("GET", "/pair/" + code, ua(""), "").status);
}
@Test public void pageNeedsValidSecret() {
    assertEquals(200, api.handle("GET", "/k/" + phone.secret, noHeaders(), "").status);
    assertEquals(403, api.handle("GET", "/k/wrong", noHeaders(), "").status);
}
@Test public void statusReflectsStream() {
    assertEquals("{\"state\":\"waiting\"}", api.handle("GET", "/api/status", key(phone), "").body);
    hub.attach(sink);
    assertEquals("{\"state\":\"ready\"}", api.handle("GET", "/api/status", key(phone), "").body);
}
@Test public void textIsTypedWhenStreaming() {
    hub.attach(sink);
    assertEquals(200, api.handle("POST", "/api/text", key(phone), "{\"text\":\"a!\"}").status);
    assertEquals(Arrays.asList("key:65:false", "text:!"), sink.events);
}
@Test public void textWithoutStreamIs409() { assertEquals(409, api.handle("POST", "/api/text", key(phone), "{\"text\":\"a\"}").status); }
@Test public void tooLongTextIs413() { hub.attach(sink); assertEquals(413, api.handle("POST", "/api/text", key(phone), json("a".repeat(4001))).status); }
@Test public void badJsonIs400() { hub.attach(sink); assertEquals(400, api.handle("POST", "/api/text", key(phone), "{").status); }
@Test public void unknownSpecialKeyIs400() { hub.attach(sink); assertEquals(400, api.handle("POST", "/api/key", key(phone), "{\"key\":\"f13\"}").status); }
@Test public void api_rejectsRemovedPhone() {
    store.remove(phone.id);
    assertEquals(401, api.handle("GET", "/api/status", key(phone), "").status);
    assertEquals(401, api.handle("POST", "/api/text", key(phone), "{\"text\":\"a\"}").status);
}
@Test public void unknownPathIs404() { assertEquals(404, api.handle("GET", "/etc/passwd", noHeaders(), "").status); }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.phonekeyboard.PhoneKeyboardApiTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Implement `ApiResponse` and `PhoneKeyboardApi`** per the route table. Never put request text into a log or an exception message.

- [ ] **Step 4: Run tests to verify they pass** (expected PASS).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardApiTest.java
git commit -m "phone-keyboard: pairing, page and typing API"
```

---

### Task 5: Server and service lifecycle

**Files:**
- Modify: `app/build.gradle` (dependencies): add `implementation 'org.nanohttpd:nanohttpd:2.3.1'`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PhoneKeyboardServer.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PhoneKeyboardService.java`
- Modify: `app/src/main/java/com/limelight/PcView.java` (`onCreate`) and `app/src/main/java/com/limelight/Game.java` (`onCreate`, after `instance = this;`): call `PhoneKeyboardService.get(this).refresh()`
- Test: `app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardServiceTest.java` (Robolectric)

**Interfaces:**
- Consumes: `PhoneKeyboardApi`, `ApiResponse` (Task 4), store and codes (Task 2), hub (Task 3).
- Produces:
  - `final class PhoneKeyboardServer extends NanoHTTPD` with `PORT = 47900` and constructor `(PhoneKeyboardApi api)`. `serve(IHTTPSession)` reads the body (up to 64 KiB, using `content-length`), calls `api.handle(method, uri, headers, body)`, and maps `ApiResponse` to `newFixedLengthResponse` plus headers.
  - `final class PhoneKeyboardService` with `static PhoneKeyboardService get(Context)` (process singleton on the application context). Methods: `void refresh()`, which runs the server iff `!store.isEmpty() || codes.hasActive()` (start/stop idempotent, failure logged and non-fatal). `String startPairing()` issues a code, refreshes, and returns `http://{lanIp}:47900/pair/{code}`, or `null` without a LAN IPv4. `void endPairing()` cancels codes and refreshes. `void setOnPairedListener(Runnable)` runs on the main thread. `PairedPhoneStore store()`, `boolean isRunning()`, and `static String findLanIpv4(Context)`, taking the first IPv4 of the active network's `LinkProperties`.
  - Page HTML is loaded once from the asset `phone_keyboard/index.html` (Task 6).

- [ ] **Step 1: Write the failing tests** (start/stop are observable through `isRunning()`; the server binds a real port, so the tests stop it in `@After`):

```java
@Test public void notRunningWithNoPhonesAndNoPairing() { service.refresh(); assertFalse(service.isRunning()); }
@Test public void runsWhilePairingAndStopsAfter() {
    service.startPairing();       // may return null under Robolectric (no LAN IP); still starts
    assertTrue(service.isRunning());
    service.endPairing();
    assertFalse(service.isRunning());
}
@Test public void keepsRunningWhileAPhoneIsPaired() {
    service.store().addPhone("x"); service.refresh(); assertTrue(service.isRunning());
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testNonRoot_gameDebugUnitTest --tests 'com.limelight.phonekeyboard.PhoneKeyboardServiceTest'`
Expected: compilation FAIL.

- [ ] **Step 3: Add the dependency and implement `PhoneKeyboardServer` and `PhoneKeyboardService`.** `startPairing` must start the server even when `findLanIpv4` returns null. Construct the singleton with a package-private constructor so tests can build one per test.

- [ ] **Step 4: Add the `refresh()` calls** in `PcView.onCreate` and `Game.onCreate`.

- [ ] **Step 5: Run the new tests and the full suite** (expected BUILD SUCCESSFUL). Startup tests must not bind the port: no phones are paired in tests.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardServiceTest.java app/src/main/java/com/limelight/PcView.java app/src/main/java/com/limelight/Game.java
git commit -m "phone-keyboard: embedded server, running only while needed"
```

---

### Task 6: The keyboard page

**Files:**
- Create: `app/src/main/assets/phone_keyboard/index.html` (single file, inline CSS and JS)
- Test: `app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardPageTest.java` (Robolectric, reads the asset)

**Interfaces:**
- Consumes: API routes from Task 4. The secret is the last path segment of `location.pathname` (`/k/{secret}`), sent as header `X-Pheme-Key`.

Page behaviour (from the spec):
- A single-line input and a **Send** button inside a `<form>`, so Enter on the phone sends. `autocomplete="off"`, `autocorrect="off"`. Password mode also sets `autocapitalize="off"`.
- **Password mode** toggle switches the input's `type` between `password` and `text`.
- After a 200, the box clears. After a 409, it keeps the text and shows "Waiting for a stream…". After a 401, it shows "This phone isn't paired anymore. Pair it again from Pheme on the TV." and stops polling.
- A special-keys row: Enter, ⌫, Tab, Esc, ←, ↑, ↓, →, mapped to `POST /api/key` with `enter, backspace, tab, esc, left, up, down, right`.
- Status line: "Connected", "Waiting for a stream…", or "Can't reach Pheme on the TV". Polls `GET /api/status` every 2 s while visible, and immediately on `visibilitychange` to visible.
- Help line: "Nothing typed? In Apollo's web UI, allow keyboard input for this client."
- Phone-sized, large touch targets, `prefers-color-scheme` light/dark. No external URLs: no `http`/`https` link, script or stylesheet.

- [ ] **Step 1: Write the failing test**

```java
@Test public void pageIsSelfContainedAndUsesTheApi() throws Exception {
    String html = readAsset("phone_keyboard/index.html");
    assertFalse("no external resources", html.matches("(?s).*(src|href)=\"https?://.*"));
    assertTrue(html.contains("/api/text")); assertTrue(html.contains("/api/key")); assertTrue(html.contains("/api/status"));
    assertTrue(html.contains("X-Pheme-Key"));
    for (String k : new String[]{"enter","backspace","tab","esc","left","up","down","right"}) assertTrue(k, html.contains("'" + k + "'"));
    assertTrue(html.contains("allow keyboard input for this client"));
}
```

- [ ] **Step 2: Run to verify it fails** (`--tests 'com.limelight.phonekeyboard.PhoneKeyboardPageTest'`, expected FAIL: asset missing).

- [ ] **Step 3: Write `index.html`** per the behaviour list.

- [ ] **Step 4: Run the test to verify it passes.** Then check the page by eye: open it in a desktop browser as a local file, with the API calls failing. It must render and show "Can't reach Pheme on the TV".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/phone_keyboard/index.html app/src/test/java/com/limelight/phonekeyboard/PhoneKeyboardPageTest.java
git commit -m "phone-keyboard: the phone page"
```

---

### Task 7: Pairing dialog, game menu entry and paired-phones settings

**Files:**
- Modify: `app/build.gradle`: add `implementation 'com.google.zxing:core:3.5.3'`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PairPhoneDialog.java`
- Create: `app/src/main/java/com/limelight/phonekeyboard/PairedPhonesDialog.java`
- Modify: `app/src/main/java/com/limelight/GameMenu.java`: new option after "Send keys" (~line 266)
- Modify: `app/src/main/res/xml/preferences.xml`: new `Preference` key `phone_keyboard_paired_phones` after `microphone_preview` (~line 255)
- Modify: `app/src/main/java/com/limelight/preferences/StreamSettings.java`: click listener next to the other `findPreference(...)` listeners (~line 764)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Test: `app/src/test/java/com/limelight/phonekeyboard/PairPhoneDialogTest.java` (Robolectric)

**Interfaces:**
- Consumes: `PhoneKeyboardService.startPairing()`, `endPairing()`, `setOnPairedListener()`, `store()` (Task 5).
- Produces:
  - `PairPhoneDialog.show(Activity)`. The QR is drawn with ZXing `QRCodeWriter` to a `Bitmap` (black on white, about 600 px) in an `ImageView`. Below it: the URL as text and the tip lines. The dialog shows "Phone paired" when the listener fires, and calls `endPairing()` on dismiss. If `startPairing()` returns null, it shows only `phone_keyboard_no_network`.
  - `PairedPhonesDialog.show(Activity)` lists `label · last used <relative time>`. Tapping an entry confirms, then removes it and calls `service.refresh()`. The neutral button "Pair a new phone" opens `PairPhoneDialog`.
  - `static Bitmap PairPhoneDialog.renderQr(String text, int sizePx)`, testable on its own.

Strings (English, with Italian equivalents in `values-it`):

| Key | English |
|---|---|
| `game_menu_phone_keyboard` | Phone keyboard |
| `phone_keyboard_title` | Type from your phone |
| `phone_keyboard_scan` | Scan with your phone. Bookmark the page to reuse it. |
| `phone_keyboard_paired` | Phone paired. You can close this. |
| `phone_keyboard_ip_tip` | If the bookmark stops working, give the TV a fixed IP in your router or pair again. |
| `phone_keyboard_apollo_tip` | Nothing typed? In Apollo's web UI, allow keyboard input for this client. |
| `phone_keyboard_no_network` | Connect the TV to Wi-Fi or Ethernet first. |
| `title_phone_keyboard_paired_phones` | Paired phones |
| `summary_phone_keyboard_paired_phones` | Phones that can type on the PC through Pheme |
| `phone_keyboard_pair_new` | Pair a new phone |
| `phone_keyboard_remove_confirm` | Remove %1$s? Its link will stop working. |
| `phone_keyboard_none` | No phones paired yet. |

- [ ] **Step 1: Write the failing test**

```java
@Test public void qrRendersSquareBitmap() {
    Bitmap b = PairPhoneDialog.renderQr("http://192.168.178.35:47900/pair/abc", 600);
    assertEquals(600, b.getWidth()); assertEquals(600, b.getHeight());
}
```

- [ ] **Step 2: Run to verify it fails** (`--tests 'com.limelight.phonekeyboard.PairPhoneDialogTest'`, expected compilation FAIL).

- [ ] **Step 3: Add ZXing and implement both dialogs, the strings, the menu option** (a UI navigation action like "Send keys": `hideMenu()` then `PairPhoneDialog.show(game)`), the preference and its click listener.

- [ ] **Step 4: Run the new test and the full suite** (expected BUILD SUCCESSFUL. `LayoutInflationTest` covers any new layout).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/com/limelight/phonekeyboard/ app/src/test/java/com/limelight/phonekeyboard/PairPhoneDialogTest.java app/src/main/java/com/limelight/GameMenu.java app/src/main/res/xml/preferences.xml app/src/main/java/com/limelight/preferences/StreamSettings.java app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "phone-keyboard: pairing QR, game menu entry and paired phones settings"
```

---

### Task 8: Device verification and docs

**Files:**
- Modify: `CHANGELOG.md` (under `## Unreleased`, an `### Added` bullet)
- Modify: `README.md` (one line in the "About this fork" list)

- [ ] **Step 1: Build and install** with `./gradlew :app:assembleNonRoot_gameDebug`, then `adb install -r` the armeabi-v7a APK on the Chromecast and the arm64 APK on the phone.

- [ ] **Step 2: Pair.** Start a stream on the TV, open the game menu, choose "Phone keyboard", and scan the QR with an iPhone (Safari) and an Android phone (Chrome). Expected: the page opens and the TV shows "Phone paired".

- [ ] **Step 3: Type.** In Notepad on the PC, send `Password1 è@€!` with mixed-case letters. Expected: exactly that text, in order. In a game chat box, send a line and press the page's Enter. Expected: the line is posted.

- [ ] **Step 4: Persistence.** Close the menu (the page still types). End the stream (the page shows "Waiting for a stream…"). Start a new stream (the page reconnects with no reload). Restart the TV app and reopen the bookmark (it still works).

- [ ] **Step 5: Removal.** Remove the phone in Settings → Paired phones. Expected: the page shows "isn't paired anymore". With no phones paired and no dialog open, `adb shell "ss -ltn | grep 47900"` shows nothing.

- [ ] **Step 6: Load check.** Stream for 3 minutes at the usual settings with the page open. Expected: no new frame drops compared with a run without the page (overlay "frames dropped" stays as before).

- [ ] **Step 7: Update CHANGELOG and README, run the full suite, commit**

```bash
./gradlew :app:testNonRoot_gameDebugUnitTest
git add CHANGELOG.md README.md
git commit -m "docs: phone keyboard in changelog and README"
```
