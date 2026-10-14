# Reading position: persistence and sync

Reopening a document must land the reader where they stopped. That holds on the same device after a tab close, app kill or reboot, and on another signed-in device. An unexpected jump to page one when saved progress exists is a high-priority defect.

## What's stored

| Field | Meaning |
|---|---|
| `documentId` | SHA-256 of the PDF bytes. The same file has the same id on every device. |
| `pageIndex` | 0-based page at the top edge of the viewport. |
| `pageOffset` | How far down that page the viewport top is, 0–1. A fraction, not pixels, so it means the same thing on any screen size, orientation or zoom. |
| `horizontalOffset` | Horizontal scroll as a 0–1 fraction (only matters when zoomed in). |
| `zoom` | Relative to fit-width, 1–4. |
| `pageCount` | Used to reject a position that doesn't fit the document. |
| `hlc`, `deviceId` | The ordering stamp (see below). |
| `schemaVersion` | Currently 1. |

## Flowing-text mode

Flowing text shows the PDF's extracted text reflowed to the screen, like Play Books. It keeps one block per source page, so a position is "page N, this fraction of the way through that page" in both modes. Switching modes, changing text size or theme, and syncing with a device in the other mode all land in the same place. The fraction is of the page's height in original-pages mode and of the page's reflowed text in flowing-text mode. These line up closely for text pages but aren't exact.

EPUBs are always flowing text. For them, a "page" is a chapter (spine item), and the fraction counts blocks (paragraphs, headings, images) through that chapter. That doesn't depend on screen or text size either.

The mode is remembered per document on each device (`documents.readingMode`) and isn't synced. Text size and theme are device-wide preferences.

## Layers

1. **In memory.** `ReaderController` tracks the viewport position.
2. **Local, durable.** Room/SQLite in WAL mode with `synchronous=FULL`. Writes are debounced at 500 ms and forced at least every 3 s during continuous scrolling, so a reading session is never more than a few seconds from disk. Pause and close also flush, but nothing depends on that, because the OS can kill the app without warning.
3. **Server.** WorkManager pushes unsynced rows with a network constraint and exponential backoff, plus a periodic job. It survives process death and reboots, so offline progress goes out once connectivity returns, even if the app isn't reopened.
4. **Restore.** On open, the local row is used immediately. If there's none and the user is signed in, the reader waits up to 1.5 s for the server before starting at page one.

## Conflict policy: last writer wins

Positions are ordered by `(hlc, deviceId)`, and the larger one wins. Device ids compare by UTF-16 code units (Kotlin's `String.compareTo`; the Mac app does the same). `hlc` is a hybrid logical clock. It's wall-clock milliseconds, but always greater than anything the device has issued or seen from the server. So once a device has seen another device's position, its next save orders after it, even if its own wall clock is behind. The clock is reseeded from the database on startup, so it never goes backwards across restarts.

Every write goes through one compare-and-store, `ReadingPositionRepository.mergeRemote` / `saveLocal`. That covers local saves, server responses, retries and late responses alike. Consequences:

- A late or out-of-order network response can't overwrite newer progress.
- "Start from the beginning" is just a newer save, so it propagates. ("Furthest page wins" would make it impossible to restart.)
- Older offline reading loses to newer reading done elsewhere in the meantime.

### While a document is open

- When the Mac app becomes active again, open books check the server. Local reading is saved first, and the server's position counts only if it's newer than everything saved on this device. If the book hasn't been touched since, the reader moves there with a notice. If it's scrolled while the request is out, it's offered instead.

- Nothing is saved until the initial restore has been applied. The list lays out at page 0 before that, and those reports are ignored.
- Scroll reports are only accepted under the layout (width and zoom) the position was last applied to. A rotation or zoom change re-applies the position before reports count again.
- If a newer server position arrives after opening:
  - If the user hasn't moved yet, the reader jumps to it ("Moved to page N, where you left off on another device").
  - If the user has already started reading, it's never forced. A snackbar offers "Go there", and this device's position is re-saved so it stays the latest.

## Server contract

Implemented by [server/](../server/README.md) (Node, SQLite, no dependencies).

```
GET /v1/reading-positions/{documentId}
  200 {position}   |   404 if none

PUT /v1/reading-positions/{documentId}
  body {position}
  200 {position}   — whatever the server holds *after* the compare
```

`Authorization: Bearer <token>`. The `position` JSON uses the field names in the table above.

The server **must** apply the same rule as the client: store the incoming position only if `(hlc, deviceId)` is greater than the stored one, then return the stored one. Inside a single transaction or conditional write, this makes `PUT` idempotent and order-independent. When the client gets back a position with its own stamp, it marks its row synced.

The Android app talks to the deployed Worker (`https://reader-sync.satyanarayanv2050.workers.dev`) by default and syncs once someone signs in. Optional settings in `local.properties`:

```
sync.baseUrl=https://another-server.example.dev
sync.token=<bearer token from server/bin/admin.js, used while nobody is signed in>
google.webClientId=<id>.apps.googleusercontent.com
```

Positions are kept per account. People sign in with email and password (the email confirmed with an emailed code) or with Apple or Google; one email is one account however they sign in. See [server/README.md](../server/README.md).

On Android, sign in from the account button at the top of the Library: email and password (new accounts and password resets confirm a 6-digit emailed code), or "Continue with Google" when the server has Google turned on and the build has `google.webClientId`. Signing in pushes any reading done before it; a token the server rejects (password reset elsewhere, signed out everywhere) signs the device out and asks to sign in again, keeping unsynced positions until then.

Sign in with Google on Android needs, in one Google Cloud project: an OAuth client of type **Web application** (its id is `google.webClientId`, and it goes in the server's `GOOGLE_CLIENT_IDS`, because Google issues Android's ID tokens for it), and an OAuth client of type **Android** for package `io.github.satyanvm.reader` with the signing certificate's SHA-1 (`./gradlew :app:signingReport`).

On the Mac, sign in under Reader → Settings… → Account, or from the Library's "Sign In to Sync" button. The server address is built into the app. The Mac app pushes pending positions a few seconds after each change, retries with exponential backoff, runs every 15 minutes, and pushes again when the network comes back and on every launch.

## Monitoring

`RestoreTelemetry` records `FIRST_OPEN`, `RESTORED_LOCAL`, `RESTORED_REMOTE`, `REMOTE_APPLIED_LATE`, `USER_RESTART`, `RESTORE_MISMATCH` and `UNEXPECTED_RESET`. The current implementation logs under the `ReaderRestore` tag (on the Mac, the unified log category `ReaderRestore`) and keeps on-device counters. `UNEXPECTED_RESET` is logged at error level. Forward these to the analytics backend once one exists.
