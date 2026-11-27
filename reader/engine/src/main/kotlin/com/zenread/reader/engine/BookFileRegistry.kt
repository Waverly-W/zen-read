package com.zenread.reader.engine

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps unguessable tokens to book files so the reader page can fetch
 * `https://appassets.androidplatform.net/book/<token>.<ext>` without seeing real paths.
 * Only registered files are reachable.
 */
@Singleton
class BookFileRegistry @Inject constructor() {
    private val files = ConcurrentHashMap<String, File>()

    /** Returns the URL the page should fetch. The extension lets foliate-js sniff CBZ/FBZ. */
    fun register(file: File, extension: String): Registration {
        val token = UUID.randomUUID().toString()
        val name = "$token.$extension"
        files[name] = file
        return Registration(name, "${ReaderOrigin.ORIGIN}${ReaderOrigin.BOOK_PATH}$name")
    }

    fun unregister(registration: Registration) {
        files.remove(registration.name)
    }

    internal fun resolve(name: String): File? = files[name]

    data class Registration(internal val name: String, val url: String)
}
