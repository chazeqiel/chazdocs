# ChazDocs

A lightweight, standalone Wear OS document reader built with Jetpack Compose Material 3 for round AMOLED smartwatches (tuned for the **OnePlus Watch 4**).

ChazDocs (`com.chazdocs.wear`) is a fast, zero-bloat alternative to WristDocs and ReadEra on Wear OS. It discovers files pushed directly to `/sdcard/Documents` via ADB—with no phone companion app, no cloud account, and no heavy external document SDKs—producing an R8-shrunk release APK of ~2.6 MB.

---

## Purpose & Design Goals

- **Offline & Standalone**: Reads documents stored locally on the watch (`/sdcard/Documents` and subfolders) immediately after an `adb push`, with a `MediaStore` fallback.
- **Built for Round AMOLED Screens**: Pure `#000000` AMOLED Black default theme (plus Midnight, Sepia, and Paper themes) with curved Wear Material 3 lists (`TransformingLazyColumn`).
- **Zero-Bloat Parsers**: Uses Android's native `PdfRenderer` for PDFs and pure-Kotlin `ZipInputStream` / DOM XML / streaming parsers for EPUB, DOCX, PPTX, Markdown, and plain text.
- **Watch-First Ergonomics**: Hardware rotating crown zoom/scroll, pinch-to-zoom with 2D momentum fling, multi-tap overlays, and document-scoped back-gesture protection.

---

## Supported Formats

| Format | Extensions | Capabilities |
| :--- | :--- | :--- |
| **PDF** | `.pdf` | Continuous vertical page scrolling, single-frame GPU center & pinch-centroid zoom (`1.0x–4.5x`), 2D momentum fling physics, pure-Kotlin `/Outlines` bookmark TOC (with page-jump fallback), cancellable `/Contents` + `/ToUnicode` CMap full-text search, optional night color inversion, and persistent bottom `page/total` badge. |
| **EPUB** | `.epub` | Pure-Kotlin OPF spine reader, EPUB3 `nav.xhtml` & NCX hierarchical TOC, HTML block reflow, and chapter navigation. |
| **Word** | `.docx` | Pure-Kotlin OOXML `word/document.xml` reader supporting headings (auto-TOC), bulleted lists, quotes, code blocks, and tables (`cell | cell`). |
| **PowerPoint** | `.pptx` | Slide-by-slide reader preserving `presentation.xml.rels` slide order, slide titles, bullets, tables, and speaker notes. |
| **Markdown & Text** | `.md`, `.markdown`, `.txt`, `.log` | Streaming parser with automatic heading/chapter TOC extraction, large-paragraph chunking, and milestone fallback TOC. |

---

## Controls & Gestures

### Inside a Document
- **Turnable Knob / Crown**:
  - **Zoom mode** (default): Zooms into the screen center (`1.0x–4.5x` on PDFs; `10sp–28sp` reflowable font size on text/EPUB/DOCX/PPTX, or canvas zoom if configured in Settings).
  - **Scroll mode**: Smoothly scrolls through pages or text. Switchable in **Settings → Crown in Document** or the Triple-Tap Menu.
- **Two-Finger Pinch**: Zooms directly toward the centroid between your fingers.
- **One-Finger Drag & Flick**: Scrolls vertically through all pages; when zoomed, pans in 2D with exponential-decay momentum fling and seamless cross-page overflow.
- **Single Tap (`1×`)**: Toggles the minimal auto-hiding HUD (clock, zoom level, crown mode, and chapter/slide controls).
- **Double Tap (`2×`)**: Opens the **Table of Contents** overlay (crown scrolls entries; swipe left-to-right or tap `×` to return to the document).
- **Triple Tap (`3×`)**: Opens the **Reader Menu** with:
  - **Search in Doc** (keyword search with snippet results & jump-to-location)
  - **Main Menu** (saves reading position and returns to Home)
  - **Settings**
  - **Crown: Zoom / Scroll** quick toggle
  - **Table of Contents** shortcut
  - **Reset Zoom**
  - **Night PDF Colors** / **Theme** switcher
- **Back Gesture (Left-to-Right Swipe)**: Disabled **only** while actively reading inside a document so horizontal panning never accidentally closes your book. Swipe-to-dismiss remains active on Home, Settings, and inside the TOC, Menu, and Search overlays.

### Main Menu (Home) & Settings
- **Crown**: Scrolls all lists and menus (`HomeScreen`, `SettingsScreen`, `TocOverlay`, `ReaderMenuOverlay`, `DocumentSearchOverlay`) at all times.
- **Top Controls**: Refresh library, single-button **Sort** switcher (`Date` → `Name` → `Size`), and **Settings**.
- **Format Filters**: Appears automatically when multiple document formats are present.

---

## Quick Start (ADB)

For full step-by-step instructions (including wireless pairing and troubleshooting), see **[docs/ADB_TUTORIAL.md](docs/ADB_TUTORIAL.md)**.

```powershell
# 1. Connect to your watch over wireless ADB (use the connection port from Developer options -> Wireless debugging)
adb connect <WATCH_IP>:<CONNECT_PORT>

# 2. Install the release APK (-r keeps data/progress, -g grants runtime permissions)
adb -s <WATCH_IP>:<CONNECT_PORT> install -r -g app/build/outputs/apk/release/app-release.apk

# 3. Grant All-Files access so ChazDocs can scan /sdcard/Documents immediately after adb push
adb -s <WATCH_IP>:<CONNECT_PORT> shell appops set com.chazdocs.wear MANAGE_EXTERNAL_STORAGE allow

# 4. Push documents to the watch
adb -s <WATCH_IP>:<CONNECT_PORT> push "notes.pdf" /sdcard/Documents/
```

---

## Building & Testing from Source

### Requirements
- **JDK 17**
- **Android SDK** with `compileSdk = 37` and Build Tools `37.0.0` (configure your local SDK path in `local.properties`, which is git-ignored)

### Commands (Windows PowerShell)
```powershell
# Run JVM unit tests
.\gradlew.bat testDebugUnitTest

# Build debug & R8-minified release APKs
.\gradlew.bat assembleDebug assembleRelease

# Run Android Lint on release
.\gradlew.bat lintVitalRelease
```

The release APK (`isMinifyEnabled = true`, `isShrinkResources = true`, debug-signed for easy ADB sideloading) is output to:
`app/build/outputs/apk/release/app-release.apk`

---

## Project Structure

```text
app/src/main/java/com/chazdocs/wear/
├── MainActivity.kt                  # Home library screen (swipe-to-dismiss enabled)
├── ReaderActivity.kt                # Document reader host (swipe-to-dismiss disabled inside doc)
├── SettingsActivity.kt              # Settings screen (swipe-to-dismiss enabled)
├── data/
│   ├── AppSettings.kt               # SharedPreferences + StateFlow preferences & reading progress
│   └── DocumentScanner.kt           # /sdcard/Documents + MediaStore scanner, sorter & permission helper
├── engine/
│   ├── DocumentModels.kt            # DocFormat, DocumentItem, TocEntry, ReflowBlock, TextChapter
│   ├── PdfDocumentEngine.kt         # PdfRenderer + LRU bitmap cache + aspect-ratio cache + night invert
│   ├── PdfOutlineParser.kt          # Shared PdfLowLevelReader (xref/XRef/ObjStm) + /Outlines parser
│   ├── PdfTextExtractor.kt          # Cancellable PDF /Contents + /ToUnicode CMap text search
│   ├── EpubDocumentEngine.kt        # Pure-Kotlin ZIP + DOM XML OPF/NAV/NCX/HTML parser
│   ├── OfficeDocumentEngine.kt      # Pure-Kotlin DOCX & PPTX OOXML parsers
│   └── TextDocumentEngine.kt        # Streaming Markdown & plain-text parser
└── ui/
    ├── components/
    │   ├── ChazIcons.kt             # Lightweight custom ImageVector icons
    │   └── GestureDetectors.kt      # MultiTapTracker, GPU zoom/pan math, pinch+fling & multi-tap modifiers
    ├── screens/
    │   ├── HomeScreen.kt            # Library view with Continue Reading, sort switcher & format filters
    │   ├── DocumentReaderScreen.kt  # Continuous PDF viewport + reflowable text viewport + HUD + overlays
    │   ├── TocOverlay.kt            # Double-tap Table of Contents overlay
    │   ├── ReaderMenuOverlay.kt     # Triple-tap Reader Menu overlay
    │   ├── DocumentSearchOverlay.kt # In-document keyword search overlay
    │   └── SettingsScreen.kt        # Crown mode, theme, sensitivity & storage settings
    └── theme/
        └── ChazDocsTheme.kt         # AMOLED Wear Material 3 theme & reader color palettes
```
