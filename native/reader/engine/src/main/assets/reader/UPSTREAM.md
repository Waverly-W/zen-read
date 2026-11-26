# Vendored reader engine

Copied from the Modu Reader Flutter app (`assets/foliate-js/` at commit
83e39a79), which itself forks [foliate-js](https://github.com/johnfactotum/foliate-js) (MIT).

Only the runtime files are vendored: `index.html`, `reader-bootstrap.js`, `src/`, `dist/`.
The Kotlin host injects `window.flutter_inappwebview.callHandler` (see
`ReaderBridge.kt`), so no file here needs to change. If you do patch a file,
note the change at the top of that file and list it below.

## Local changes

- `src/paginator.js` — `uncollapse()`: a collapsed range at the end of a text node now
  measures from the start of the next text node. Before, restoring a page-start CFI that
  fell on a split text node opened the previous page.
- `src/reader-fonts.js` — `readerFontCSS()`: `fontName` `serif` / `sans-serif` / `monospace` is
  emitted as an unquoted CSS generic family (no `@font-face`), and the same names as
  `englishFontName` use `local()` system faces like upstream's `system`. Upstream quoted every
  name, so `"serif"` was looked up as a font literally named serif.
