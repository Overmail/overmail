package es.jvbabi.overmail.server.util

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

/**
 * Extracts the readable text from an HTML mail body, for mails that ship no text/plain part.
 * Block elements and `<br>` become line breaks, everything else collapses into running text.
 */
object HtmlToText {

    private val blockTags = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "main",
        "table", "tr", "ul", "ol", "li", "blockquote", "pre", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )

    fun convert(html: String): String {
        val body = Jsoup.parse(html).body()
        // Their text content is markup, not prose. Removed up front because a NodeVisitor
        // cannot skip a subtree once it has descended into it.
        body.select("style, script, noscript, template").remove()

        val out = StringBuilder()
        body.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> {
                        val text = node.text()
                        if (text.isNotBlank()) out.append(text)
                        // Whitespace-only nodes are the separator between inline elements
                        // ("<a>..</a> <a>..</a>"); dropped entirely, the two texts would fuse.
                        else if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                    }

                    node is Element && (node.tagName() == "br" || node.tagName() in blockTags) ->
                        out.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node !is Element) return
                // The link text alone can hide the actual target ("Klick hier"): magic links and
                // confirmation URLs live in the href, so it stays part of the extracted text.
                if (node.tagName() == "a") {
                    val href = node.attr("href")
                    if (href.isNotBlank() && !href.startsWith("#") && href != node.text()) {
                        out.append(" ($href)")
                    }
                }
                // Table cells get a space, not a line break: layout tables are the norm in mail
                // HTML, and one line per cell would tear every row apart.
                if (node.tagName() == "td" || node.tagName() == "th") out.append(' ')
                if (node.tagName() in blockTags) out.append('\n')
            }
        })

        return out.toString()
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
