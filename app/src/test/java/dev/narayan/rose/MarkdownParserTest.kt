package dev.narayan.rose

import dev.narayan.rose.document.MarkdownParserFactory
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {

    private val parser = MarkdownParserFactory.parser

    @Test
    fun testHeadingsParsing() {
        val markdown = """
            # Heading 1
            ## Heading 2
            ### Heading 3
            #### Heading 4
            ##### Heading 5
            ###### Heading 6
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertNotNull(doc)

        var count = 0
        var child = doc.firstChild
        while (child != null) {
            if (child is Heading) {
                count++
                assertEquals(count, child.level)
            }
            child = child.next
        }
        assertEquals(6, count)
    }

    @Test
    fun testListsAndTaskListsParsing() {
        val markdown = """
            - Item A
            - Item B
            - [ ] Task Unchecked
            - [x] Task Checked

            1. Numbered 1
            2. Numbered 2
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertNotNull(doc)

        var bulletCount = 0
        var orderedCount = 0
        var child = doc.firstChild
        while (child != null) {
            if (child is BulletList) bulletCount++
            if (child is OrderedList) orderedCount++
            child = child.next
        }

        assertEquals(1, bulletCount)
        assertEquals(1, orderedCount)
    }

    @Test
    fun testCodeBlockAndInlineCode() {
        val markdown = """
            Here is `inline code`.

            ```kotlin
            fun main() {
                println("Hello ROSE")
            }
            ```
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertNotNull(doc)

        var fencedCodeFound = false
        var language: String? = null
        var child = doc.firstChild
        while (child != null) {
            if (child is FencedCodeBlock) {
                fencedCodeFound = true
                language = child.info
                assertTrue(child.literal.contains("println(\"Hello ROSE\")"))
            }
            child = child.next
        }

        assertTrue(fencedCodeFound)
        assertEquals("kotlin", language)
    }

    @Test
    fun testTableParsing() {
        val markdown = """
            | Feature | Status | Description |
            | :--- | :---: | ---: |
            | Markdown | Supported | Full CommonMark + GFM |
            | Editor | Supported | Text editing with save |
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertNotNull(doc)

        var tableFound = false
        var child = doc.firstChild
        while (child != null) {
            if (child is TableBlock) {
                tableFound = true
            }
            child = child.next
        }

        assertTrue(tableFound)
    }

    @Test
    fun testLinksAndImagesParsing() {
        val markdown = """
            [ROSE Project](https://github.com/NarayanChetri/ROSE)
            ![Logo](assets/logo.png)
        """.trimIndent()

        val doc = parser.parse(markdown)
        assertNotNull(doc)

        var linkFound = false
        var imageFound = false

        fun search(node: Node) {
            if (node is Link) {
                linkFound = true
                assertEquals("https://github.com/NarayanChetri/ROSE", node.destination)
            }
            if (node is Image) {
                imageFound = true
                assertEquals("assets/logo.png", node.destination)
            }
            var c = node.firstChild
            while (c != null) {
                search(c)
                c = c.next
            }
        }

        search(doc)
        assertTrue(linkFound)
        assertTrue(imageFound)
    }

    @Test
    fun testMalformedMarkdownResilience() {
        val malformed = """
            ### Unclosed formatting **bold with no close
            | Broken | Table |
            | without | proper | columns | or | dividers
            ```
            unclosed code block
            > broken quote
            [Unclosed link](https://
            <invalid<html<<<tags>
        """.trimIndent()

        // Should not throw or crash
        val doc = parser.parse(malformed)
        assertNotNull(doc)
        assertTrue(doc.firstChild != null)
    }

    @Test
    fun testMarkdownSanitizerUnclosedBold() {
        val input = "**this is a try sentence"
        val sanitized = dev.narayan.rose.document.MarkdownSanitizer.sanitize(input)
        assertEquals("**this is a try sentence**", sanitized)

        val doc = parser.parse(sanitized)
        assertNotNull(doc)

        var strongEmphasisFound = false
        var child = doc.firstChild?.firstChild
        while (child != null) {
            if (child is StrongEmphasis) {
                strongEmphasisFound = true
                val innerText = (child.firstChild as? Text)?.literal
                assertEquals("this is a try sentence", innerText)
            }
            child = child.next
        }
        assertTrue("Expected StrongEmphasis node to be parsed from unclosed **", strongEmphasisFound)
    }

    @Test
    fun testMarkdownSanitizerDelimiterSpacing() {
        val input = "** bold text ** and ~~ strike ~~"
        val sanitized = dev.narayan.rose.document.MarkdownSanitizer.sanitize(input)
        assertEquals("**bold text** and ~~strike~~", sanitized)
    }

    @Test
    fun testMarkdownSanitizerHeadingsWithoutSpace() {
        val input = "#Heading 1\n##Heading 2"
        val sanitized = dev.narayan.rose.document.MarkdownSanitizer.sanitize(input)
        assertEquals("# Heading 1\n## Heading 2", sanitized)
    }

    @Test
    fun testMarkdownSanitizerHtmlConversion() {
        val input = "This is <b>bold</b> and <i>italic</i> and <br>break"
        val sanitized = dev.narayan.rose.document.MarkdownSanitizer.sanitize(input)
        assertEquals("This is **bold** and *italic* and   \nbreak", sanitized)
    }

    @Test
    fun testMarkdownSanitizerPreservesCodeBlocks() {
        val input = """
            ```kotlin
            val x = 5 * 2 * 3
            **not bold in code**
            ```
        """.trimIndent()
        val sanitized = dev.narayan.rose.document.MarkdownSanitizer.sanitize(input)
        assertEquals(input, sanitized)
    }
}

