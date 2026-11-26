// Zen Read local change (see UPSTREAM.md): `serif` / `sans-serif` / `monospace`
// are accepted as unquoted CSS generic families for the body font and as
// local() system faces for the English font.
// Limit the English face even when the imported file also contains CJK glyphs.
// Chinese/full-width punctuation (and curly quotes used in Chinese) stays with
// the body face. No text nodes are split: selection/CFIs/TTS remain unchanged.
export const englishUnicodeRange = 'U+0000-024F, U+1E00-1EFF, U+2000-200A, U+2010-2011, U+2022, U+20A0-20CF';
const bookMarker = '/* modu-book-font-fallback */';
const quote = value => '"' + String(value).replace(/[\\"\n\r\f]/g,
  char => '\\' + char.charCodeAt(0).toString(16) + ' ') + '"';

const genericFamilies = new Set(['serif', 'sans-serif', 'monospace']);
const systemSans = 'local("Arial"), local("Roboto"), local("Noto Sans"), local("DejaVu Sans"), local("Liberation Sans")';
const localSources = {
  system: systemSans,
  'sans-serif': systemSans,
  serif: 'local("Noto Serif"), local("NotoSerif-Regular"), local("Droid Serif"), local("Georgia"), local("Times New Roman"), local("Liberation Serif"), local("DejaVu Serif")',
  monospace: 'local("Droid Sans Mono"), local("Roboto Mono"), local("Noto Sans Mono"), local("Courier New"), local("Liberation Mono"), local("DejaVu Sans Mono")',
};

export function readerFontCSS({ fontName = 'book', fontPath = '',
  englishFontName, englishFontPath } = {}) {
  let faces = '';
  const generic = genericFamilies.has(fontName);
  const body = fontName === 'book' ? null : fontName === 'system'
    ? 'system-ui' : generic ? fontName : quote(fontName);
  if (body && fontName !== 'system' && !generic && fontPath) {
    faces += `@font-face { font-family: ${body}; src: url(${quote(fontPath)}); font-display: block; }`;
  }
  const local = Object.prototype.hasOwnProperty.call(localSources, englishFontName) ? localSources[englishFontName] : null;
  if (!englishFontName || englishFontName === 'follow' ||
      (!local && !englishFontPath)) {
    return { faces, family: body ? `font-family: ${body} !important;` : '' };
  }
  const english = quote('ModuEnglish_' + englishFontName);
  const source = local ?? `url(${quote(englishFontPath)})`;
  faces += `@font-face { font-family: ${english}; src: ${source}; unicode-range: ${englishUnicodeRange}; font-display: block; }`;
  return { faces, family: `${body ? '' : bookMarker}
    font-family: ${english}, ${body ?? 'var(--modu-book-font-family, serif)'} !important;` };
}

const captured = new WeakSet();
// Preserve publisher CJK faces when only the English font is overridden. Called
// before font discovery/pagination for ordinary, preloaded and footnote frames.
export function captureBookFontFamilies(doc, styles, ownStyles = []) {
  if (!doc?.body || captured.has(doc) || !String(styles).includes(bookMarker)) return;
  const saved = ownStyles.map(style => style.textContent);
  let families;
  try {
    ownStyles.forEach(style => { style.textContent = ''; });
    const elements = [doc.documentElement, doc.body, ...doc.body.querySelectorAll('*')];
    // Read all computed styles before writing to avoid per-element reflow.
    families = elements.filter(el => el.style).map(el =>
      [el, doc.defaultView.getComputedStyle(el).fontFamily || 'serif']);
  } finally {
    ownStyles.forEach((style, i) => { style.textContent = saved[i]; });
  }
  for (const [el, family] of families) el.style.setProperty('--modu-book-font-family', family);
  captured.add(doc);
}
