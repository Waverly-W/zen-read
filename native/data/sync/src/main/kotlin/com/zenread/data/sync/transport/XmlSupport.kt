package com.zenread.data.sync.transport

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Namespace-aware DOM parsing with DTDs and external entities disabled. */
internal object SafeXml {
    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            isXIncludeAware = false
            // Not every platform parser knows every feature; each is best effort.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw SyncException.Protocol("Malformed XML response")
        }
    }
}

/** Child elements with this local name (and namespace, when given), in document order. */
internal fun Element.children(localName: String, namespace: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    var node: Node? = firstChild
    while (node != null) {
        if (node is Element && node.matches(localName, namespace)) result += node
        node = node.nextSibling
    }
    return result
}

internal fun Element.child(localName: String, namespace: String? = null): Element? =
    children(localName, namespace).firstOrNull()

/** All descendant elements with this local name (and namespace, when given). */
internal fun Element.descendants(localName: String, namespace: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    fun walk(parent: Node) {
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node is Element) {
                if (node.matches(localName, namespace)) result += node
                walk(node)
            }
            node = node.nextSibling
        }
    }
    walk(this)
    return result
}

internal fun Element.text(): String = textContent.orEmpty().trim()

private fun Element.matches(localName: String, namespace: String?): Boolean {
    val name = this.localName ?: tagName.substringAfter(':')
    // Servers that forget the DAV: namespace declaration still match by local name.
    return name == localName && (namespace == null || namespaceURI == null || namespaceURI == namespace)
}
