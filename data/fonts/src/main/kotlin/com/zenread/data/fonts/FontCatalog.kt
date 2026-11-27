package com.zenread.data.fonts

import com.zenread.core.model.ReaderFont
import com.zenread.reader.engine.ReaderFontFiles

/** A font shipped in the APK under `assets/fonts/`. */
data class BundledFont(
    val fileName: String,
    val label: String,
    val license: String,
) {
    val font: ReaderFont.Bundled get() = ReaderFont.Bundled(fileName)

    /** Path for `AssetManager.open`, matching what the reader serves at `/fonts/bundled/`. */
    val assetPath: String get() = "${ReaderFontFiles.ASSET_DIRECTORY}/$fileName"
}

/**
 * A font offered as an on-demand download. Every URL must serve the same bytes: the file is
 * accepted only when its size and SHA-256 match, then stored as an installed font.
 */
data class DownloadableFont(
    val id: String,
    val label: String,
    val format: FontFormat,
    val sizeBytes: Long,
    val sha256: String,
    /** Tried in order: the official source first, then a mirror that is usually reachable from mainland China. */
    val urls: List<String>,
    val license: String,
    val homepage: String,
) {
    /** The installed file name, see [FontFileNames.installedName]. */
    val fileName: String get() = FontFileNames.installedName(sha256, format)
    val font: ReaderFont.Installed get() = ReaderFont.Installed(fileName)
}

/**
 * Fonts the app offers besides system and user-imported ones. All are under the SIL Open Font
 * License 1.1 and redistributed unmodified; see `LICENSES/` and `NOTICE` at the repository root.
 */
object FontCatalog {
    private const val OFL = "SIL OFL 1.1"

    /** 65 KB Latin face. Large CJK faces are downloads to keep the APK small. */
    val bundled: List<BundledFont> = listOf(
        BundledFont("AtkinsonHyperlegibleNext-Regular.ttf", "Atkinson Hyperlegible Next", OFL),
    )

    val downloadable: List<DownloadableFont> = listOf(
        DownloadableFont(
            id = "source-han-serif-cn",
            label = "思源宋体 Source Han Serif CN",
            format = FontFormat.OTF,
            sizeBytes = 11_626_108,
            sha256 = "3754ea669c530e2473354f8f6d9f79680a44d7e26ec7d00eeabee4a7e0753c5d",
            urls = listOf(
                "https://raw.githubusercontent.com/adobe-fonts/source-han-serif/7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
                "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-serif@7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
            ),
            license = OFL,
            homepage = "https://github.com/adobe-fonts/source-han-serif",
        ),
        DownloadableFont(
            id = "lxgw-wenkai-lite",
            label = "霞鹜文楷 轻便版 LXGW WenKai Lite",
            format = FontFormat.TTF,
            sizeBytes = 13_949_472,
            sha256 = "f799a538a3355877e597a0ae04050e897932c9ae7689eb9227c2953c5dd549ea",
            urls = listOf(
                "https://github.com/lxgw/LxgwWenKai-Lite/releases/download/v1.530/LXGWWenKaiLite-Regular.ttf",
                "https://cdn.jsdelivr.net/gh/lxgw/LxgwWenKai-Lite@v1.530/fonts/TTF/LXGWWenKaiLite-Regular.ttf",
            ),
            license = OFL,
            homepage = "https://github.com/lxgw/LxgwWenKai-Lite",
        ),
        DownloadableFont(
            id = "literata",
            label = "Literata",
            format = FontFormat.TTF,
            sizeBytes = 955_132,
            sha256 = "b41138c9373112f32abb589cc22e8674b06ed4048b0c513be922bdd26f274440",
            urls = listOf(
                "https://raw.githubusercontent.com/google/fonts/5026800714d07231937d92eb4cceabf06e05d74e/ofl/literata/Literata%5Bopsz,wght%5D.ttf",
                "https://cdn.jsdelivr.net/gh/google/fonts@5026800714d07231937d92eb4cceabf06e05d74e/ofl/literata/Literata%5Bopsz,wght%5D.ttf",
            ),
            license = OFL,
            homepage = "https://github.com/googlefonts/literata",
        ),
    )

    fun downloadable(id: String): DownloadableFont? = downloadable.firstOrNull { it.id == id }
}

/** Naming rules for files in the installed fonts directory. */
object FontFileNames {
    private const val HASH_CHARS = 16
    private val HEX = Regex("[0-9a-f]{64}")

    /**
     * Installed fonts are named after their content: `<first 16 hex of SHA-256>.<ext>`. The same
     * bytes always get the same name (re-imports deduplicate), a name never changes meaning (the
     * WebView may cache it), and nothing from the user's file name reaches the file system.
     */
    fun installedName(sha256Hex: String, format: FontFormat): String {
        val hex = sha256Hex.lowercase()
        require(HEX.matches(hex)) { "Not a SHA-256 hex digest" }
        return "${hex.take(HASH_CHARS)}.${format.extension}"
    }

    /** True for names [installedName] produces; anything else in the directory is ignored. */
    fun isInstalledName(name: String): Boolean {
        val stem = name.substringBefore('.')
        val format = FontFormat.fromExtension(name.substringAfter('.', ""))
        return format != null && stem.length == HASH_CHARS && stem.all { it in '0'..'9' || it in 'a'..'f' } &&
            name == "$stem.${format.extension}"
    }
}
