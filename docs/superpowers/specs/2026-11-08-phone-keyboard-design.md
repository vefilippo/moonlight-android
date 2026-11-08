# Phone keyboard for Pheme: design

Date: 2026-11-08. Status: draft for approval.

## Intent

While streaming to the TV (Chromecast with Google TV) with only a gamepad, the user regularly
needs to type text on the PC: a **password** in a login box, or a **line of game chat**. Today
they walk to the PC and type there. That's slow at home and impossible on a remote
session away from home. The Android TV on-screen keyboard is too clumsy with a gamepad.

The fix: the user's **phone becomes a keyboard for the PC**, through Pheme on the TV. The user
selects a text box on the PC (with the gamepad), opens a saved page on the phone, types, and
the text lands in that box on the PC.

## Goals

- Type text from the phone into whatever has focus on the streamed PC: chat, passwords,
  login fields, browser boxes.
- **Nothing to install on the phone.** A web page served by Pheme works the same on Android
  and iPhone browsers.
- **Pair once, then just reopen the page.** After the first QR scan, the phone keeps a
  permanent link (bookmark or home-screen icon). Opening it at any time connects to the
  stream, without rescanning.
- The page keeps working for the **whole stream**, whether or not any Pheme menu is open.
- Works anywhere Pheme works, at home or away, as long as the phone and the TV share a network.

## Non-goals

- Voice typing (Windows Win+H from a gamepad combo). That was discussed and **parked for
  later**. Don't build it here.
- A native Android or iOS app.
- Reading text **from** the PC (two-way sync, showing the PC's current field). This is
  one-way: phone to PC.
- Mouse or trackpad control from the phone.
- Reaching the TV across networks (cloud relay, internet exposure).

## Anti-goals (it fails even if it technically works)

- Anyone else on the same Wi-Fi can type into the PC.
- A password is stored, logged or shown on the TV.
- Text arrives garbled: wrong characters for the PC's (Italian) keyboard layout, or dropped
  or reordered characters.
- The user has to rescan the QR code or dig through menus every time they want to type.
- It interferes with the stream: added latency, stutter, or extra CPU load during normal play.
  The dongle's CPU is weak (see `docs/investigations/2026-11-08-fec-lag.md`).

## User experience

### First pairing

1. During a stream, the user opens Pheme's in-stream game menu and picks **"Phone keyboard"**,
   or opens it from Pheme's settings outside a stream.
2. The TV shows a **QR code** and the same address as short text, plus one line: "Scan with
   your phone. Bookmark the page to reuse it."
3. The phone opens the page, which is now paired. The TV dialog confirms "Phone paired" and
   can be closed. The phone stays connected.

### Everyday use

- The user selects a text box on the PC, opens the bookmarked page on the phone, types, and
  sends.
- If a stream is running, the page is ready immediately. If not, it shows **"Waiting for a
  stream…"** and becomes ready by itself when a stream starts, with no reload.
- If Pheme isn't running or the TV can't be reached, the page says so plainly ("Can't reach
  Pheme on the TV"). It keeps retrying in the background while it's open.

### The page

- A large **single-line** text box with a **Send** button, and **Enter on the phone's keyboard
  sends**. The user composes the whole text on the phone, then sends it, so typos can be fixed
  before anything reaches the PC. Keystrokes are not streamed live (decided). There is no
  multi-line mode (my call). Any newline in pasted text is turned into a space, so a paste
  can't submit a chat or form part-way. The Enter key in the special-key row is the only way to
  press Enter on the PC.
- **Password mode** toggle: the input is masked, nothing is kept after sending, and the box
  clears on send. In normal mode the box also clears on send, so the next chat line starts
  empty.
- A row of **special keys** that act immediately on the PC: Enter, Backspace, Tab, Esc and
  the four arrows. Enter is separate from Send, so you can send text without submitting, or
  submit a form.
- A small status line: connected / waiting for a stream / can't reach Pheme.
- Works on current Safari (iPhone) and Chrome (Android), phone-sized, light and dark mode,
  large touch targets. Plain and fast, with no external network requests: everything is
  served from the TV.

### Managing phones

- Pheme settings get a **"Paired phones"** screen: each paired phone, with a name or label
  and when it was last used, a **Remove** action, and **"Pair a new phone"** (shows the QR).
- Removing a phone invalidates its link at once.

## How text reaches the PC

Two existing paths in Pheme send keyboard input to the host:

- **Key presses**: `NvConnection.sendKeyboardInput(keyCode, direction, modifiers, flags)`,
  Windows virtual-key codes. This is used for special keys (see `Game.sendKeys`,
  `KeyboardTranslator`).
- **Unicode text**: `NvConnection.sendUtf8Text(String)`, used by the Android soft keyboard
  through `Game.enqueueCommitText`, which chunks to 512 bytes and paces at 15 ms.

Decided:

- **Letters a-z/A-Z, digits 0-9 and space are sent as real key presses**, with Shift for
  capitals. Those keys sit in the same place on US and Italian layouts, and some games and
  secure fields ignore Unicode text injection but accept key presses.
- **Everything else** (punctuation, symbols, accented letters, emoji) goes through the
  **Unicode text path**. Key-press codes for symbols depend on the host's layout (the PC is
  Italian), so guessing them risks wrong characters, which is an anti-goal.
- Special keys from the key row are sent as key presses (VK_RETURN, VK_BACK, VK_TAB,
  VK_ESCAPE, arrows).
- **Order must be preserved** across the two paths within one Send. A string mixing letters
  and symbols must arrive in order.
- This path must work regardless of the existing `enableCommitText` preference, which gates
  the soft-keyboard path today.
- **Host assumption (my call):** the host is Apollo (the user's `vefilippo/Apollo` fork) or a
  recent Sunshine, both of which support Unicode text injection. Older hosts (GeForce
  Experience) aren't supported, and no capability check is needed.
- **Apollo client permissions (my call):** Apollo can deny keyboard input per client. Then
  the page would look connected while nothing gets typed. The pairing screen and the page's
  help line say: "Nothing typed? In Apollo's web UI, allow keyboard input for this client."

## Lifetime and connection

- The phone-keyboard server lives as long as **Pheme's process** does, not just the stream
  screen. That way the page can show "Waiting for a stream…" and connect when one starts.
  While a stream is active, received text goes to that stream's connection. Otherwise sends
  are refused, and the page shows it's waiting.
- It runs on a **fixed port**, so a bookmarked link keeps working across streams and TV
  restarts.
- Known limit: the link contains the TV's IP address. If the TV's address changes, the
  bookmark stops working until the user re-pairs or gives the TV a fixed IP in the router.
  The pairing screen should show this tip.

## Security

- Each paired phone gets its **own long random secret**, embedded in its link. Every request
  must carry a valid secret. Requests without one are rejected before anything is typed.
- Pairing is only possible while the pairing QR is on screen. The QR's one-time pairing code
  expires when the dialog closes or after a few minutes.
- Secrets are stored in Pheme's private storage and survive restarts until removed in
  "Paired phones".
- Text is never logged. Pheme's logs may record that a phone sent N characters, never the
  content.
- Known limit, accepted: plain HTTP on the local network. HTTPS with a self-signed certificate
  shows scary browser warnings, especially on iPhone, and defeats "just reopen the page".
  Someone sniffing the same Wi-Fi could see traffic. That's acceptable for home and trusted
  networks. The pairing screen does not need to mention it.

## Constraints

- Pheme is an Android app (Java, `app/` module), forked from Artemis/Moonlight. Follow the
  existing code style and structure.
- Target device: Chromecast with Google TV (Android 14, armeabi-v7a, weak CPU, 2 GB RAM). It
  must not add measurable load while idle or during streaming.
- Also has to work on Android phones running Pheme (same code), though the main use is the TV.
- Unit tests run with Robolectric (`./gradlew :app:testNonRoot_gameDebugUnitTest`), and the
  suite is currently fully green. New logic (text-to-key splitting, secret checks, request
  handling) should be covered by tests that don't need a device.
- UI strings go through Android string resources. English is required; Italian is welcome.
- No external network requests from the page (no CDNs). The TV may be on a network without
  internet.

## Left to the builder

- The embedded HTTP server: a small library or a hand-rolled `ServerSocket`, and the
  request/response format between page and Pheme (simple POSTs, long-poll, WebSocket…).
- The port number, as long as it's fixed and unlikely to clash.
- QR code generation: a small library or a bundled encoder.
- Exact page layout and styling, within the experience above.
- How the server is hosted inside the app process (Application-scoped component vs.
  service), and how it finds the active stream's connection.
- How to label phones in "Paired phones" (user-agent-derived name, "Phone 1"…).
- Pacing between key presses, if needed for reliability.
