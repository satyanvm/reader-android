# Reader

A PDF and EPUB reader that always reopens where you stopped, on the same device or another one. Books can be read as original pages (PDF) or as flowing text, Play Books style.

This repo has the Android app (Kotlin, Jetpack Compose, Room, WorkManager) at the top level and the macOS app (SwiftUI, PDFKit, Vision, SQLite) in [mac/](mac/README.md). Both store the same reading position and sync through the server in [server/](server/README.md). The design is in [docs/position-sync.md](docs/position-sync.md).

## Build and test (Android)

Requires JDK 17 and an Android SDK with platform 35.

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

`local.properties` needs `sdk.dir=...`. Add `sync.baseUrl` and `sync.token` to turn on sync (see the doc).

For the Mac app, see [mac/README.md](mac/README.md).

## Layout (Android)

| Path | What |
|---|---|
| `model/` | `ReadingPosition`, `Stamp`, `HybridClock` |
| `data/` | Room database, `PositionStore`, `ReadingPositionRepository` (the conflict policy), `DocumentRepository` (import + content-hash ids) |
| `sync/` | `SyncApi` contract, `HttpSyncApi`, `SyncEngine`, WorkManager scheduling |
| `reader/` | `ReaderController` (restore state machine), `PositionSaver` (debounced durable writes), `PageLayout` (fraction ↔ pixel math), `PdfDocument` (PdfRenderer), Compose screen |
| `text/` | Flowing text: `PdfLineExtractor` (positioned, styled lines via PdfBox), `Ocr` (ML Kit for scanned pages), `Reflow` (lines → paragraphs/headings), `PageAssembly` (running headers, cross-page paragraphs), `TextDocument` (per-page pipeline + cache), `EpubBook` (EPUB parsing + chapter positions) |
| `library/` | Recent documents with progress |
| `telemetry/` | Restore outcome tracking, including `UNEXPECTED_RESET` |

## Acceptance scenarios → tests

| # | Scenario | Covered by |
|---|---|---|
| 1 | Close and reopen resumes at page 187 | `ReaderControllerTest`: reopen resumes at the saved page and offset |
| 2–3 | Browser/app restart, device reboot | `RoomPersistenceTest` (real SQLite file closed and reopened), `SyncScenariosTest`: survives restart, clock reseeded from disk; `ReaderControllerTest`: saved after debounce without any lifecycle event |
| 4 | Another signed-in device | `SyncScenariosTest`: position read on one device opens on another; `ReaderControllerTest`: new device waits briefly for the server |
| 5 | Offline, then reconnect | `SyncScenariosTest`: offline reading syncs; newer reading elsewhere wins over older offline progress |
| 6 | Open before sync finishes | `ReaderControllerTest`: slow sync applies a newer remote / never yanks a reading user; scroll reports during loading never overwrite |
| 7 | Delayed / out-of-order responses | `SyncScenariosTest`: late or out-of-order responses; `ReaderControllerTest`: older server position arriving late is ignored |
| 8 | Long PDFs, zoom, viewport sizes | `PageLayoutTest`; `ReaderControllerTest`: rotation and zoom; end-of-document clamp |

The unit tests drive the controller the way the UI does. Running the real app on a device or emulator is still the final check.
