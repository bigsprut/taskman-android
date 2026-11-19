package io.github.bigsprut.taskman.markdown

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.ui.markdown.MarkdownBlock
import io.github.bigsprut.taskman.ui.markdown.MarkdownInline
import io.github.bigsprut.taskman.ui.markdown.MarkdownParser
import org.junit.Test

/**
 * Тесты разбора Markdown: модель должна быть предсказуемой и не падать
 * на разметке, которую пользователь набрал руками с ошибками.
 */
class MarkdownParserTest {

    @Test
    fun emptyStringYieldsNoBlocks() {
        assertThat(MarkdownParser.parse("")).isEmpty()
        assertThat(MarkdownParser.parse("   \n\n  ")).isEmpty()
    }

    @Test
    fun hardLineBreakIsSingleLineBreak() {
        val blocks = MarkdownParser.parse("первая строка  \nвторая строка")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("первая строка\nвторая строка"))),
        )
    }

    @Test
    fun paragraphWithBoldItalicAndCode() {
        val blocks = MarkdownParser.parse("**bold** и *italic* и `code`")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(
                listOf(
                    MarkdownInline.Bold(listOf(MarkdownInline.Text("bold"))),
                    MarkdownInline.Text(" и "),
                    MarkdownInline.Italic(listOf(MarkdownInline.Text("italic"))),
                    MarkdownInline.Text(" и "),
                    MarkdownInline.Code("code"),
                ),
            ),
        )
    }

    @Test
    fun underscoreBoldAndItalicAreSupported() {
        val blocks = MarkdownParser.parse("__жирный__ и _курсив_")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(
                listOf(
                    MarkdownInline.Bold(listOf(MarkdownInline.Text("жирный"))),
                    MarkdownInline.Text(" и "),
                    MarkdownInline.Italic(listOf(MarkdownInline.Text("курсив"))),
                ),
            ),
        )
    }

    @Test
    fun linkIsParsedWithTextAndUrl() {
        val blocks = MarkdownParser.parse("[текст](https://example.com)")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(
                listOf(MarkdownInline.Link(text = "текст", url = "https://example.com")),
            ),
        )
    }

    @Test
    fun headingLevelOne() {
        val blocks = MarkdownParser.parse("# Заголовок")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Heading(1, listOf(MarkdownInline.Text("Заголовок"))),
        )
    }

    @Test
    fun headingLevelThree() {
        val blocks = MarkdownParser.parse("### Три")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Heading(3, listOf(MarkdownInline.Text("Три"))),
        )
    }

    @Test
    fun bulletListWithThreeItems() {
        val blocks = MarkdownParser.parse("- один\n- два\n- три")

        assertThat(blocks).containsExactly(
            MarkdownBlock.BulletList(
                listOf(
                    listOf(MarkdownInline.Text("один")),
                    listOf(MarkdownInline.Text("два")),
                    listOf(MarkdownInline.Text("три")),
                ),
            ),
        )
    }

    @Test
    fun orderedListKeepsStartNumber() {
        val blocks = MarkdownParser.parse("3. три\n4. четыре")

        assertThat(blocks).containsExactly(
            MarkdownBlock.OrderedList(
                start = 3,
                items = listOf(
                    listOf(MarkdownInline.Text("три")),
                    listOf(MarkdownInline.Text("четыре")),
                ),
            ),
        )
    }

    @Test
    fun orderedListStartsAtOneByDefault() {
        val blocks = MarkdownParser.parse("1. раз\n2. два")

        assertThat(blocks).containsExactly(
            MarkdownBlock.OrderedList(
                start = 1,
                items = listOf(
                    listOf(MarkdownInline.Text("раз")),
                    listOf(MarkdownInline.Text("два")),
                ),
            ),
        )
    }

    @Test
    fun nestedBulletListIsFlattenedAndKeepsAllTexts() {
        val blocks = MarkdownParser.parse(
            """
            - a
                - b1
                - b2
            - d
            """.trimIndent(),
        )

        assertThat(blocks).containsExactly(
            MarkdownBlock.BulletList(
                listOf(
                    listOf(MarkdownInline.Text("a")),
                    listOf(MarkdownInline.Text("b1")),
                    listOf(MarkdownInline.Text("b2")),
                    listOf(MarkdownInline.Text("d")),
                ),
            ),
        )
    }

    @Test
    fun blockQuoteIsParsed() {
        val blocks = MarkdownParser.parse("> цитата")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Quote(
                listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("цитата")))),
            ),
        )
    }

    @Test
    fun quoteKeepsSoftLineBreaks() {
        val blocks = MarkdownParser.parse("> первая\n> вторая")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Quote(
                listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("первая\nвторая")))),
            ),
        )
    }

    @Test
    fun fencedCodeBlockWithLanguage() {
        val blocks = MarkdownParser.parse("```kotlin\nval x = 1\n```")

        assertThat(blocks).containsExactly(
            MarkdownBlock.CodeBlock(language = "kotlin", code = "val x = 1"),
        )
    }

    @Test
    fun fencedCodeBlockWithoutLanguage() {
        val blocks = MarkdownParser.parse("~~~\nplain text\n~~~")

        assertThat(blocks).containsExactly(
            MarkdownBlock.CodeBlock(language = null, code = "plain text"),
        )
    }

    @Test
    fun strikethroughIsParsed() {
        val blocks = MarkdownParser.parse("~~удалено~~")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(
                listOf(MarkdownInline.Strikethrough(listOf(MarkdownInline.Text("удалено")))),
            ),
        )
    }

    @Test
    fun horizontalRuleBecomesDivider() {
        val blocks = MarkdownParser.parse("до\n\n---\n\nпосле")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("до"))),
            MarkdownBlock.Divider,
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("после"))),
        )
    }

    @Test
    fun paragraphsAreSeparatedByBlankLine() {
        val blocks = MarkdownParser.parse("Первый абзац.\n\nВторой абзац.")

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("Первый абзац."))),
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("Второй абзац."))),
        )
    }

    @Test
    fun brokenInlineMarkdownIsShownAsText() {
        // Незакрытый жирный и одиночная звёздочка — не повод падать.
        val bold = MarkdownParser.parse("**незакрытый жирный")
        val singleStar = MarkdownParser.parse("одиночная * звёздочка")

        assertThat(bold).containsExactly(
            MarkdownBlock.Paragraph(listOf(MarkdownInline.Text("**незакрытый жирный"))),
        )
        assertThat(singleStar).isNotEmpty()
    }

    @Test
    fun unclosedCodeFenceDoesNotThrow() {
        val blocks = MarkdownParser.parse("```\nкод без закрытия")

        assertThat(blocks).containsExactly(
            MarkdownBlock.CodeBlock(language = null, code = "код без закрытия"),
        )
    }

    @Test
    fun taskDescriptionFixtureIsRendered() {
        // Образец из фикстуры задач: обязателен к поддержке.
        val markdown = "Описание задачи №28. **markdown**, [ссылка](https://example.com), список:\n" +
            "- пункт 1\n" +
            "- пункт 2"

        val blocks = MarkdownParser.parse(markdown)

        assertThat(blocks).containsExactly(
            MarkdownBlock.Paragraph(
                listOf(
                    MarkdownInline.Text("Описание задачи №28. "),
                    MarkdownInline.Bold(listOf(MarkdownInline.Text("markdown"))),
                    MarkdownInline.Text(", "),
                    MarkdownInline.Link(text = "ссылка", url = "https://example.com"),
                    MarkdownInline.Text(", список:"),
                ),
            ),
            MarkdownBlock.BulletList(
                listOf(
                    listOf(MarkdownInline.Text("пункт 1")),
                    listOf(MarkdownInline.Text("пункт 2")),
                ),
            ),
        )
    }
}
