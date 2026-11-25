# WORDcraft 1.6.0

## Windows HTTPS compatibility fix

- Added a fingerprint-verified public Certum Trusted Root CA certificate for official downloads when an older Java or operating-system trust store lacks the CA used by gov.pl. The certificate is loaded from the mod, without downloading or trusting a certificate from a failed connection.
- Added Java/runtime and trust-store diagnostics to preparation reports; passwords and custom trust-store paths are not displayed.
- Combined Java's trusted CA certificates with the Windows current-user and local-machine root stores when downloading official learning materials.
- Unavailable or empty Windows stores no longer discard Java's trusted CA certificates. Explicit JVM trust-store settings still take priority.
- Kept HTTPS certificate-chain, expiry and hostname verification enabled.
- Replaced the generic internet warning with specific HTTPS, DNS, timeout and disk-error messages. The question-bank report now includes the failed operation and the underlying exception chain.
- Added local TLS regression tests and a Windows CI check against the official download endpoints.

## Minecraft 1.21.1 port

- Added a Fabric build for Minecraft 1.21.1, compatible with Java 21.
- Adapted screens, buttons, key bindings, media textures and the background download overlay to the Minecraft 1.21.1 APIs.
- Retained the existing 1.6.0 features, Linux download fixes and native media libraries for Linux, Windows and macOS.

## Changes in 1.6.0

- Answers can now be selected and submitted before or during video playback in practice and exam modes, allowing users to continue without watching the entire video.
- Failed media downloads now trigger a random replacement question. Exam replacements preserve the question type and point value without counting the failed download as an incorrect answer.
- Added bounded recovery attempts, avoiding previously failed media and falling back to cached content or questions without media.
- Added an experimental background download option for all learning images and videos, allowing users to keep playing while downloading.
- Added calculated disk-space warnings, estimated download and temporary-file sizes, and available-space checks before bulk downloading.
- Added buttons to open the local media folder directly from the settings and download screens.
- Added pause and resume controls. Bulk downloads reuse verified local files and support resuming partial downloads.
- Bulk downloading now automatically pauses after three consecutive failures and when importing or refreshing the question bank.
- Added an in-game download overlay in the lower-left area, showing file counts, completed and total media size, a progress bar, transfer speed in MB/s, and estimated time remaining.
- Download counters and progress now refresh once per second. Transfer speed and ETA are smoothed to reduce visual jumps, while pause and completion updates remain immediate.
- Background downloads stop cleanly when Minecraft closes; downloaded files remain available for the next session.
- Added a custom project icon.
- Reduced the JAR size through stronger lossless compression while retaining Linux, Windows, and macOS support.

Requirements for this build: Minecraft 1.21.1, Fabric, and Java 21 or later.
