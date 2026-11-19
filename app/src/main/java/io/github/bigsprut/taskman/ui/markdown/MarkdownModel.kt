package io.github.bigsprut.taskman.ui.markdown

/**
 * Разобранный Markdown: блоки документа верхнего уровня.
 *
 * Модель намеренно не зависит от Compose — её удобно проверять юнит-тестами
 * и кэшировать, а отрисовка живёт отдельно в [MarkdownText].
 */
sealed interface MarkdownBlock {
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    /** Маркированный список: пункт — это набор инлайн-элементов. */
    data class BulletList(val items: List<List<MarkdownInline>>) : MarkdownBlock

    /** Нумерованный список; [start] — число первого пункта в разметке. */
    data class OrderedList(val start: Int, val items: List<List<MarkdownInline>>) : MarkdownBlock

    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Инлайн-элемент внутри абзаца, заголовка или пункта списка.
 *
 * Форматирование вложенное ([Bold] внутри [Italic] и наоборот), поэтому
 * отрисовка может просто обойти дерево стилей.
 */
sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Bold(val inlines: List<MarkdownInline>) : MarkdownInline
    data class Italic(val inlines: List<MarkdownInline>) : MarkdownInline
    data class Strikethrough(val inlines: List<MarkdownInline>) : MarkdownInline
    data class Code(val text: String) : MarkdownInline

    /** Ссылка: [text] — подпись, [url] — назначение. */
    data class Link(val text: String, val url: String) : MarkdownInline
}
