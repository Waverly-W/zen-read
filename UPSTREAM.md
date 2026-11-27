# Upstream sources

Zen Read is an independently modified, GPL-3.0-or-later Android-native rewrite of
**Modu Reader**, which itself derives from **Anx Reader** and **ReadAny**. Thank you to
their authors and contributors. Zen Read is not affiliated with, endorsed by, or an
official release of any of them.

## Modu Reader

- Repository: https://github.com/sobranie2406/modureader
- Ported from commit: `83e39a79`
- License: GPL-3.0-or-later
- Role: behavior specification for every feature. The Flutter/Dart code was rewritten in
  Kotlin + Jetpack Compose module by module, then removed from this repository; the git
  history before the removal still contains it. Ported files name their upstream source
  in the file header.

## Anx Reader

- Repository: https://github.com/anxcye/anx-reader
- License: MIT, preserved in `LICENSES/Anx-Reader-MIT.txt`.
- Role: reader page scripts (via Modu Reader), vendored under
  `reader/engine/src/main/assets/reader/`.

## ReadAny

- Repository: https://github.com/codedogQBY/ReadAny
- License: GPL-3.0-or-later, preserved in `LICENSES/ReadAny-GPL-3.0-or-later.txt`.
- Role: AI reading, hybrid RAG and TTS logic (via Modu Reader), ported to Kotlin in
  `data:ai` and `data:tts`.

## foliate-js

- Repository: https://github.com/johnfactotum/foliate-js
- License: MIT
- Role: the reading engine, running in a WebView. Copied from Modu Reader's
  `assets/foliate-js/`; local patches are listed in
  `reader/engine/src/main/assets/reader/UPSTREAM.md`.

## edge-tts

- Repository: https://github.com/rany2/edge-tts (7.3.0)
- License: LGPL-3.0, preserved in `LICENSES/edge-tts-LGPL-3.0.txt`.
- Role: Edge Read Aloud `Sec-MS-GEC` token generation, checked against upstream test vectors.

Upstream updates are reviewed and ported explicitly. See `NOTICE` for copyright lines and
`ARCHITECTURE.md` for how each feature maps to the upstream code.
