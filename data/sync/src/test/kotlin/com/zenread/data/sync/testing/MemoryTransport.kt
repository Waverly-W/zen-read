package com.zenread.data.sync.testing

import com.zenread.data.sync.transport.RemoteEntry
import com.zenread.data.sync.transport.SyncException
import com.zenread.data.sync.transport.SyncTransport
import java.io.File
import java.util.TreeMap

/** An in-memory [SyncTransport] that records operations and can fail on demand. */
class MemoryTransport(override val identity: String = "memory") : SyncTransport {
    override val label = "memory"
    val files = TreeMap<String, ByteArray>()
    private val directories = sortedSetOf<String>()
    val operations = mutableListOf<String>()

    /** Throws for writes whose path matches. */
    var failWrites: (String) -> Boolean = { false }

    fun paths(prefix: String) = files.keys.filter { it.startsWith(prefix) }

    override suspend fun list(path: String): List<RemoteEntry>? {
        operations += "LIST $path"
        val prefix = if (path.isEmpty()) "" else "$path/"
        val children = LinkedHashMap<String, RemoteEntry>()
        for ((key, value) in files.tailMap(prefix)) {
            if (!key.startsWith(prefix)) break
            val rest = key.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) children[rest] = RemoteEntry(rest, false, value.size.toLong(), modifiedAt = 0)
            else children.putIfAbsent(rest.substring(0, slash), RemoteEntry(rest.substring(0, slash), true))
        }
        directories.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') && it != path }
            .forEach { children.putIfAbsent(it.removePrefix(prefix), RemoteEntry(it.removePrefix(prefix), true)) }
        return if (children.isEmpty() && path !in directories) null else children.values.toList()
    }

    override suspend fun stat(path: String): RemoteEntry? =
        files[path]?.let { RemoteEntry(path.substringAfterLast('/'), false, it.size.toLong()) }

    override suspend fun read(path: String, maxBytes: Long): ByteArray? {
        operations += "GET $path"
        return files[path]?.copyOf()
    }

    override suspend fun download(path: String, target: File): Boolean {
        operations += "GET $path"
        val bytes = files[path] ?: return false
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    override suspend fun write(path: String, bytes: ByteArray, contentType: String) {
        operations += "PUT $path"
        if (failWrites(path)) throw SyncException.Http(500, "injected failure")
        files[path] = bytes.copyOf()
    }

    override suspend fun upload(path: String, source: File, sha256Hex: String?, contentType: String) =
        write(path, source.readBytes(), contentType)

    override suspend fun delete(path: String) {
        operations += "DELETE $path"
        files.remove(path)
    }

    override suspend fun ensureDirectory(path: String) {
        var current = path
        while (current.isNotEmpty()) {
            directories += current
            current = current.substringBeforeLast('/', "")
        }
    }
}
