# WORDcraft

A client-side Fabric mod for practising Polish category B driving theory questions while playing Minecraft.

Author: **Miaek** · Version: **1.6.0** · Mod code: **MIT**

This is the recovered source project for the working 1.6.0 release. It combines code recovered from that release with preserved sources of its fixes. The original development project and some comments and local variable names were unavailable. See [RECOVERY.md](RECOVERY.md).

## Features

- Import the official category B question catalogue, with automatic downloads of required images and videos.
- Practice sessions, timed exams, answer reviews and locally saved progress.
- Select and submit video answers before playback finishes.
- Automatically try another question when a media download fails.
- Experimental download of all learning media in the background, with a disk-space warning, pause/resume and folder shortcuts.
- In-game download progress, file counts, transfer speed and ETA; displays refresh once per second.
- Linux, Windows and macOS media playback, without bundled FFmpeg/FFprobe command-line programs.

The official question bank and learning media are **not included** in this repository or the mod JAR. They are imported or downloaded separately. The MIT license covers WORDcraft code, not these materials or the bundled third-party libraries.

## Requirements

- Minecraft **26.2**
- Fabric Loader **0.19.5 or later**
- Fabric API **0.161.0+26.2**
- **JDK 25** to build; Java 25 or later to run

Supported native bundles: Linux x86-64, Windows x86-64, macOS x86-64 and macOS ARM64.

## Build

Install a full JDK 25 and point `JAVA_HOME` to it. A Java runtime without the compiler is insufficient. Gradle is downloaded by the wrapper; no separate Gradle installation is required.

```sh
./gradlew build
```

On Windows, use `gradlew.bat build`. The first build needs an internet connection for Minecraft, Fabric, test dependencies and the media libraries. It does not require an old WORDcraft JAR, decompiler, Python or a local Minecraft installation.

Outputs:

- `build/libs/wordcraft-1.6.0.jar` — installable mod, including the supported native media libraries.
- `build/libs/wordcraft-1.6.0-sources.jar` — mod Java sources and resources.

The build filters unused command-line programs from the nested media bundles and compresses the archives. The JNI libraries and third-party notices are retained.

## Test and develop

```sh
./gradlew test
./gradlew verifyDistribution
./gradlew runClient
```

Tests use synthetic local fixtures and a local HTTP server. They cover early video answers, replacement questions, HTTP range downloads, bulk progress, pause/resume, UI method linkage, XLSX import, saved progress recovery and scheduling. UI linkage checks run without opening a game window; they do not replace a visual in-game test.

The default keys are **K** for settings and **J** for a question. They can be changed in Minecraft controls. Preferences and learning progress are stored under the game's `config/wordcraft` directory.

## Source layout

- `src/main/java/pl/wordcraft/client` — Minecraft screens, controls and download overlay.
- `src/main/java/pl/wordcraft/core` — sessions, progress, scheduling and background downloads.
- `src/main/java/pl/wordcraft/data` — question models and XLSX import.
- `src/main/java/pl/wordcraft/download` — HTTPS, remote ZIP access and the official repository.
- `src/main/java/pl/wordcraft/media` — image and video playback.
- `src/main/resources` — metadata, translations, icon, explanations and library notices.

## Licenses

WORDcraft code is provided under [MIT](LICENSE). JavaCV, JavaCPP and FFmpeg have separate licenses; the full notices and corresponding source links are included under [`src/main/resources/META-INF`](src/main/resources/META-INF). See [THIRD_PARTY.md](src/main/resources/META-INF/THIRD_PARTY.md).

Release notes are in [CHANGELOG.md](CHANGELOG.md).
