# Vendored reader engine

Copied unmodified from the Modu Reader Flutter app (`assets/foliate-js/` at commit
83e39a79), which itself forks [foliate-js](https://github.com/johnfactotum/foliate-js) (MIT).

Only the runtime files are vendored: `index.html`, `reader-bootstrap.js`, `src/`, `dist/`.
The Kotlin host injects `window.flutter_inappwebview.callHandler` (see
`ReaderBridge.kt`), so no file here needs to change. If you do patch a file,
note the change at the top of that file and list it below.

## Local changes

None.
