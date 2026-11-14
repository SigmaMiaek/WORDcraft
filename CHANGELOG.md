# WORDcraft 1.6.0

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

Requirements: Minecraft 26.2, Fabric, and Java 25 or later.
