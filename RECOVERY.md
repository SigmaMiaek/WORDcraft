# Source recovery

This project restores an independently buildable source tree for WORDcraft 1.6.0 after the original development project was lost.

The Java code was recovered from the working 1.6.0 mod and combined with ten preserved complete source files for its fixes and background download features. Missing type information and decompiler artifacts were corrected using the preserved method implementations. Resources, the project icon and third-party notices were retained.

This is a reconstruction, not an exact copy of the original project. Some original comments, local variable names, formatting and build settings cannot be recovered. The code is editable and compiles directly; the build does not copy WORDcraft class files from an existing release or patch an old mod JAR.

The project uses Gradle 9.5.1, Fabric Loom 1.17.21 and Java 25. Media dependencies are downloaded from Maven repositories at their original pinned versions. All four native platform bundles remain supported. The unused FFmpeg/FFprobe command-line programs are omitted during packaging; shared library contents remain unchanged.

On the `mc-1.21.1` branch, the recovered project has been ported to Minecraft 1.21.1 and Java 21. This branch uses Mojang's official development mappings and Loom's remapping plugin to produce an installable Fabric JAR. Screens, textures, key bindings and the download HUD use the 1.21.1 APIs; the session and download behavior from 1.6.0 is retained.

Regression tests check the recovered session, download and UI linkage behavior. Synthetic XLSX and progress-store fixtures also check import, persistence and backup recovery. The distribution verification task checks nested libraries, metadata, notices and the absence of bundled command-line programs. These checks do not establish byte-for-byte identity with the old class files or replace a visual test inside Minecraft.
