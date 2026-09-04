package com.icy.lyrics.core.lyrics.parser

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * The small, ordered XML tree consumed by the existing TTML interpretation code.
 * The same strict XML reader runs on Android and iOS. It has no network/entity
 * resolver, and limits are enforced while reading, before an unbounded tree exists.
 * Text and CDATA stay in document order; namespace declarations remain attributes
 * because Apple/AMLL timing detection inspects their values.
 */
internal fun readTtmlXml(raw: String, maxElements: Int, maxDepth: Int): Document {
  validateXmlCharacters(raw)
  val reader = xmlStreaming.newGenericReader(raw, expandEntities = true)
  val stack = mutableListOf<Element>()
  var root: Element? = null
  var elements = 0
  fun append(node: Node) {
    if (stack.size > maxDepth) throw UnsafeTtmlException("TTML nesting exceeds the safety limit")
    stack.lastOrNull()?.append(node)
  }
  try {
    while (reader.hasNext()) {
      when (reader.next()) {
        EventType.START_ELEMENT -> {
          if (++elements > maxElements) throw UnsafeTtmlException("TTML contains too many elements")
          val attributes = buildList {
            for (index in 0 until reader.attributeCount) {
              val prefix = reader.getAttributePrefix(index)
              val localName = reader.getAttributeLocalName(index)
              val value = reader.getAttributeValue(index)
              validateXmlCharacters(value)
              add(Attribute(
                qualifiedName = qualifiedName(prefix, localName),
                localName = localName,
                namespace = reader.getAttributeNamespace(index),
                value = value,
              ))
            }
            reader.namespaceDecls.forEach { namespace ->
              add(Attribute(
                qualifiedName = if (namespace.prefix.isEmpty()) "xmlns" else "xmlns:${namespace.prefix}",
                localName = namespace.prefix.ifEmpty { "xmlns" },
                namespace = "http://www.w3.org/2000/xmlns/",
                value = namespace.namespaceURI,
              ))
            }
          }
          if (attributes.map { it.namespace to it.localName }.distinct().size != attributes.size) {
            throw TtmlParseException("Duplicate XML attribute")
          }
          val element = Element(
            localName = reader.localName,
            tagName = qualifiedName(reader.prefix, reader.localName),
            namespace = reader.namespaceURI,
            attributes = NodeList(attributes),
          )
          append(element)
          if (stack.isEmpty()) {
            if (root != null) throw TtmlParseException("TTML must have one document element")
            root = element
          }
          stack += element
        }
        EventType.END_ELEMENT -> {
          val element = stack.lastOrNull() ?: throw TtmlParseException("Unexpected XML closing tag")
          if (element.localName != reader.localName || element.namespace != reader.namespaceURI) {
            throw TtmlParseException("Mismatched XML closing tag")
          }
          stack.removeAt(stack.lastIndex)
        }
        EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> {
          val value = reader.text
          validateXmlCharacters(value)
          if (stack.isEmpty() && value.isNotBlank()) throw TtmlParseException("Text outside XML document element")
          append(Node(Node.TEXT_NODE, value))
        }
        EventType.COMMENT, EventType.PROCESSING_INSTRUCTION -> append(Node(Node.COMMENT_NODE))
        EventType.DOCDECL, EventType.ENTITY_REF ->
          throw UnsafeTtmlException("TTML entity and document type nodes are not allowed")
        else -> Unit
      }
    }
  } finally {
    reader.close()
  }
  if (stack.isNotEmpty()) throw TtmlParseException("Unclosed XML element")
  return Document(root ?: throw TtmlParseException("TTML has no document element"))
}

private fun qualifiedName(prefix: String, localName: String): String =
  if (prefix.isEmpty()) localName else "$prefix:$localName"

/** XML 1.0 character constraints also apply after numeric references are decoded. */
private fun validateXmlCharacters(text: String) {
  var index = 0
  while (index < text.length) {
    val char = text[index++]
    when {
      char.isHighSurrogate() -> {
        if (index >= text.length || !text[index++].isLowSurrogate()) {
          throw TtmlParseException("Invalid XML surrogate")
        }
      }
      char.isLowSurrogate() || char == '\uFFFE' || char == '\uFFFF' ||
        (char.code < 0x20 && char != '\t' && char != '\n' && char != '\r') ->
        throw TtmlParseException("Invalid XML character")
    }
  }
}

internal open class Node(val nodeType: Int, val nodeValue: String? = null) {
  var parentNode: Node? = null
    private set
  val childNodes = NodeList<Node>()
  open val localName: String? get() = null
  val textContent: String
    get() = when (nodeType) {
      TEXT_NODE, CDATA_SECTION_NODE -> nodeValue.orEmpty()
      else -> buildString { childNodes.values.forEach { append(it.textContent) } }
    }
  fun append(child: Node) {
    child.parentNode = this
    childNodes.values += child
  }
  companion object {
    const val ELEMENT_NODE = 1
    const val TEXT_NODE = 3
    const val CDATA_SECTION_NODE = 4
    const val ENTITY_REFERENCE_NODE = 5
    const val COMMENT_NODE = 8
    const val DOCUMENT_TYPE_NODE = 10
  }
}

internal class Attribute(
  val qualifiedName: String,
  override val localName: String,
  val namespace: String,
  value: String,
) : Node(2, value)

internal class NodeList<T : Node>(initial: List<T> = emptyList()) {
  val values = initial.toMutableList()
  val length: Int get() = values.size
  fun item(index: Int): T = values[index]
}

internal class Element(
  override val localName: String,
  val tagName: String,
  val namespace: String,
  val attributes: NodeList<Attribute>,
) : Node(ELEMENT_NODE) {
  fun getAttribute(name: String): String =
    attributes.values.firstOrNull { it.qualifiedName == name }?.nodeValue.orEmpty()
  fun getAttributeNS(namespace: String, name: String): String =
    attributes.values.firstOrNull { it.namespace == namespace && it.localName == name }?.nodeValue.orEmpty()
  fun hasAttribute(name: String): Boolean = attributes.values.any { it.qualifiedName == name }
  fun getElementsByTagNameNS(namespace: String, name: String): NodeList<Element> =
    NodeList(descendantElements().filter { (namespace == "*" || it.namespace == namespace) && it.localName == name })
  fun descendantElements(): List<Element> = buildList {
    fun visit(node: Node) {
      node.childNodes.values.forEach { child ->
        if (child is Element) add(child)
        visit(child)
      }
    }
    visit(this@Element)
  }
}

internal class Document(val documentElement: Element) {
  fun getElementsByTagNameNS(namespace: String, name: String): NodeList<Element> =
    NodeList(allElements().filter { (namespace == "*" || it.namespace == namespace) && it.localName == name })
  fun getElementsByTagName(name: String): NodeList<Element> = NodeList(allElements().filter { it.tagName == name })
  private fun allElements(): List<Element> = listOf(documentElement) + documentElement.descendantElements()
}
