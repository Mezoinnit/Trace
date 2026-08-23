package com.mezo.trace.adapters.feed

import com.mezo.trace.application.port.FetchFailure
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hardened XML parsing for untrusted feeds.
 *
 * External entities, DTDs, and XIncludes are disabled — feeds are attacker-
 * controlled input (Rule 3). Bounded input is enforced upstream by the
 * fetcher's size caps.
 */
object SafeXml {

    fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        return try {
            factory.newDocumentBuilder().parse(bytes.inputStream())
        } catch (e: Exception) {
            throw FetchFailure(
                FetchFailure.Kind.NETWORK,
                "malformed xml document",
                e,
            )
        }
    }

    fun childText(element: Element, tagName: String): String? =
        element.getElementsByTagName(tagName).let { nodes ->
            if (nodes.length == 0) null else nodes.item(0).textContent?.trim()?.takeIf { it.isNotEmpty() }
        }

    fun childElement(element: Element, tagName: String): Element? =
        element.getElementsByTagName(tagName).let { nodes ->
            if (nodes.length == 0) null else nodes.item(0) as? Element
        }
}
