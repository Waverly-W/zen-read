package com.zenread.core.model

/** Formats the foliate-js engine opens directly. */
enum class BookFormat(vararg val extensions: String) {
    EPUB("epub"),
    MOBI("mobi", "azw", "azw3"),
    FB2("fb2", "fbz"),
    PDF("pdf"),
    CBZ("cbz"),
    ;

    /** Extension used when storing the file; foliate-js sniffs CBZ/FBZ by name. */
    val primaryExtension: String get() = extensions.first()

    companion object {
        fun fromFileName(name: String): BookFormat? {
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

data class Book(
    val id: Long,
    val title: String,
    val author: String,
    val format: BookFormat,
    /** Absolute path inside app-private storage. */
    val filePath: String,
    val coverPath: String?,
    /** SHA-256 of the file, used to skip duplicate imports. */
    val fileHash: String,
    /** Reading position as an EPUB CFI (foliate-js also uses CFIs for PDF pages). */
    val lastLocation: String?,
    /** 0.0 – 1.0 */
    val progress: Float,
    val createdAt: Long,
    val lastReadAt: Long?,
)
