# Biblioteki i materiały innych autorów

## JavaCV / JavaCPP

JavaCV 1.5.12 i JavaCPP 1.5.12: Copyright © Samuel Audet i współtwórcy.
Projekt korzysta z oferowanego przez autorów wariantu Apache License 2.0.
Pełne, niezmienione pliki licencji autorów są w
`licenses/JavaCV-1.5.12.txt` i `licenses/JavaCPP-1.5.12.txt`.
Źródła odpowiadające wersji:

- <https://github.com/bytedeco/javacv/tree/1.5.12>
- <https://github.com/bytedeco/javacpp/tree/1.5.12>
- <https://github.com/bytedeco/javacpp-presets/tree/1.5.12/ffmpeg>

## FFmpeg

WORDcraft korzysta z FFmpeg 7.1.1, JavaCPP Presets 1.5.12, w standardowych
artefaktach `org.bytedeco:ffmpeg:7.1.1-1.5.12` (bez `-gpl`).
Copyright © FFmpeg developers. Biblioteka jest dynamicznie ładowana przez JNI.
Nie wprowadzono zmian w binariach FFmpeg, JavaCV ani JavaCPP.

Na faktycznie uruchomionym pliku `windows-x86_64` odczytano
`avutil_license() = LGPL version 3 or later`. Konfiguracja zawiera
`--enable-shared --enable-version3` i nie zawiera `--enable-gpl` ani
`--enable-nonfree`. Zastosowanie LGPL 3 wynika także ze wspólnej konfiguracji
pozostałych platform w przypiętym skrypcie kompilacji.
Nie należy mylić licencji wrapperów Apache 2.0 z licencją natywnego FFmpeg.

Pełne teksty: `licenses/LGPL-3.0.txt`, `licenses/GPL-3.0.txt`, opis autorów
`licenses/FFmpeg-presets-1.5.12.md`. FFmpeg i składniki są udostępniane bez
gwarancji. Użytkownik może wymieniać biblioteki, badać ich działanie i modyfikować
je na warunkach odpowiednich licencji; WORDcraft nie nakłada zakazu inżynierii
wstecznej służącej debugowaniu modyfikacji takich bibliotek.

Dokładne źródła i recepta odtworzenia binariów:

- FFmpeg 7.1.1: <https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.bz2>
- JavaCPP Presets 1.5.12, łącznie z wrapperami, poprawkami oraz skryptem
  `ffmpeg/cppbuild.sh`:
  <https://github.com/bytedeco/javacpp-presets/archive/refs/tags/1.5.12.tar.gz>
- Opis budowania: <https://github.com/bytedeco/javacpp-presets/tree/1.5.12>
- Oryginalne artefakty binarne:
  <https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/7.1.1-1.5.12/>

Recepta kompilacji przypina także źródła bibliotek pomocniczych i stosowane
poprawki; samo pobranie czystego FFmpeg 7.1.1 nie zawiera tych poprawek.
Zestaw wszystkich adresów znajduje się w
`licenses/FFmpeg-native-sources.txt`. Przy dalszej publicznej dystrybucji należy
zachować teksty licencji i oznaczenia autorów oraz zapewnić odbiorcom dostęp do
odpowiadających źródeł i skryptów budowania przez cały okres wymagany licencjami.
Kod źródłowy WORDcraft jest dostarczany razem z projektem.

## Składniki natywnego FFmpeg

Dołączone teksty w `licenses/ffmpeg-dependencies/` pochodzą z wersji wskazanych
w skrypcie JavaCPP Presets 1.5.12. Mogą obejmować biblioteki wykorzystywane
tylko na części platform lub do innych kodeków niż WMV.

| Składnik | Wersja | Licencja / informacja |
| --- | --- | --- |
| zlib | 1.3.1 | zlib |
| LAME | 3.100 | GNU LGPL; tekst LGPL 2.1 i źródła w wykazie |
| Speex | 1.2.1 | BSD |
| Opus | 1.3.1 | BSD i oznaczenia autorów w COPYING |
| OpenCORE AMR | 0.1.6 | Apache 2.0 |
| VisualOn AMR-WB | 0.1.3 | Apache 2.0 |
| OpenSSL | 3.4.1 | Apache 2.0 |
| OpenH264 | 2.6.0 | BSD |
| libvpx | 1.15.1 | BSD |
| ALSA (Linux) | 1.2.14 | LGPL |
| FreeType | 2.13.3 | FreeType Project License |
| mfx_dispatch | 1.35.1 | BSD |
| nv-codec-headers | 13.0.19.0 | MIT; oznaczenie NVIDIA w załączniku |
| libxml2 | 2.9.12 | MIT |
| SRT | 1.5.4 | Mozilla Public License 2.0 |
| libwebp | 1.5.0 | BSD |
| libaom | 3.9.1 | BSD i plik PATENTS |
| SVT-AV1 | 2.3.0 | BSD 3-Clause Clear |
| zimg | 3.0.5 | WTFPL |

Portions of this software are copyright © The FreeType Project
(<https://www.freetype.org/>). All rights reserved.

This software is based in part on the work of the Independent JPEG Group.
WORDcraft nie zmienia plików JPEG znajdujących się wewnątrz FFmpeg.

Nazwy i znaki towarowe autorów nie oznaczają poparcia dla WORDcraft.
Opcjonalne sterowniki GPU i systemowe biblioteki dźwięku są udostępniane
przez system operacyjny; projekt nie dołącza zastrzeżonych sterowników.

## Oficjalne pytania i multimedia

Licencja kodu WORDcraft nie obejmuje pytań, zdjęć ani filmów egzaminacyjnych.
Nie są one dołączone do publicznego JAR ani repozytorium. Użytkownik importuje
własną lokalną kopię oryginalnych materiałów z Ministerstwa Infrastruktury.
Szczegóły źródeł, praw autorów i weryfikacji importera opisuje dokumentacja
oficjalnej bazy pytań w tym projekcie.
