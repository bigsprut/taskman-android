package io.github.bigsprut.taskman.ui.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser as IntellijMarkdownParser

/**
 * Разбор Markdown в [MarkdownBlock]-модель поверх `org.jetbrains:markdown`.
 *
 * Парсер намеренно прощает ошибки: `assertionsEnabled = false` заставляет библиотеку
 * отдавать битые конструкции простым текстом вместо `MarkdownParsingException`,
 * а собственные обёртки дополнительно подстраховывают `try/catch` — экран задачи
 * не должен падать из-за описания, набранного руками.
 *
 * Вложенные списки разворачиваются в один уровень (текст пунктов сохраняется):
 * в модели [MarkdownBlock.BulletList]/[MarkdownBlock.OrderedList] у пункта нет
 * собственных дочерних блоков, и лишняя глубина только запутала бы отрисовку.
 */
object MarkdownParser {

    // Парсер и флейвор создаются один раз: GFMFlavourDescriptor тяжёлый, а состояние
    // разбора локальное, поэтому объект безопасно переиспользовать.
    private val parser = IntellijMarkdownParser(
        flavour = GFMFlavourDescriptor(),
        assertionsEnabled = false,
        cancellationToken = CancellationToken.NonCancellable,
    )

    fun parse(markdown: String): List<MarkdownBlock> {
        if (markdown.isBlank()) return emptyList()
        val root = try {
            // Приведение к CharSequence выбирает не-deprecated перегрузку библиотеки.
            parser.buildMarkdownTreeFromString(markdown as CharSequence)
        } catch (_: Exception) {
            return textBlocks(markdown)
        }
        val blocks = try {
            parseBlocks(root, markdown)
        } catch (_: Exception) {
            return textBlocks(markdown)
        }
        // Если разбор не нашёл ни одного видимого блока (например, документ из одних
        // определений ссылок), лучше показать исходный текст, чем пустоту.
        return blocks.ifEmpty { textBlocks(markdown) }
    }

    private fun textBlocks(markdown: String): List<MarkdownBlock> =
        listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(markdown.trim()))))

    // --- Блоки ---

    private fun parseBlocks(node: ASTNode, source: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        for (child in node.children) {
            parseBlock(child, source)?.let { blocks += it }
        }
        return blocks
    }

    private fun parseBlock(node: ASTNode, source: String): MarkdownBlock? = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> paragraph(node, source)
        MarkdownElementTypes.ATX_1 -> heading(1, node, source)
        MarkdownElementTypes.ATX_2 -> heading(2, node, source)
        MarkdownElementTypes.ATX_3 -> heading(3, node, source)
        MarkdownElementTypes.ATX_4 -> heading(4, node, source)
        MarkdownElementTypes.ATX_5 -> heading(5, node, source)
        MarkdownElementTypes.ATX_6 -> heading(6, node, source)
        MarkdownElementTypes.SETEXT_1 -> heading(1, node, source)
        MarkdownElementTypes.SETEXT_2 -> heading(2, node, source)
        MarkdownElementTypes.UNORDERED_LIST -> bulletList(node, source)
        MarkdownElementTypes.ORDERED_LIST -> orderedList(node, source)
        MarkdownElementTypes.BLOCK_QUOTE -> quote(node, source)
        MarkdownElementTypes.CODE_FENCE -> fencedCode(node, source)
        MarkdownElementTypes.CODE_BLOCK -> indentedCode(node, source)
        // Горизонтальная линия приходит листовым токеном, а не составным элементом.
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownBlock.Divider
        // Определения ссылок в выводе не участвуют — как и в самом Markdown.
        MarkdownElementTypes.LINK_DEFINITION -> null
        // Структурные токены: переводы строк, маркеры списков/цитат/заголовков.
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        MarkdownTokenTypes.ATX_HEADER,
        MarkdownTokenTypes.SETEXT_1,
        MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.CODE_FENCE_START,
        MarkdownTokenTypes.CODE_FENCE_CONTENT,
        MarkdownTokenTypes.CODE_FENCE_END,
        MarkdownTokenTypes.FENCE_LANG,
        MarkdownTokenTypes.CODE_LINE,
        GFMTokenTypes.TABLE_SEPARATOR -> null
        // Всё, что не поддержано отдельной моделью (таблицы, HTML, math), показываем
        // как текст, чтобы содержимое описания не пропало.
        else -> rawBlock(node, source)
    }

    private fun paragraph(node: ASTNode, source: String): MarkdownBlock? =
        normalize(flattenInline(node, source))
            .takeIf { it.isNotEmpty() }
            ?.let { MarkdownBlock.Paragraph(it) }

    private fun heading(level: Int, node: ASTNode, source: String): MarkdownBlock =
        MarkdownBlock.Heading(level, normalize(flattenInline(node, source)))

    private fun quote(node: ASTNode, source: String): MarkdownBlock? =
        parseBlocks(node, source)
            .takeIf { it.isNotEmpty() }
            ?.let { MarkdownBlock.Quote(it) }

    private fun rawBlock(node: ASTNode, source: String): MarkdownBlock? =
        text(node, source).trim()
            .takeIf { it.isNotEmpty() }
            ?.let { MarkdownBlock.Paragraph(listOf(MarkdownInline.Text(it))) }

    private fun bulletList(node: ASTNode, source: String): MarkdownBlock? =
        listItems(node, source)
            .takeIf { it.isNotEmpty() }
            ?.let { MarkdownBlock.BulletList(it) }

    private fun orderedList(node: ASTNode, source: String): MarkdownBlock? =
        listItems(node, source)
            .takeIf { it.isNotEmpty() }
            ?.let { MarkdownBlock.OrderedList(start = listStart(node, source), items = it) }

    private fun listStart(list: ASTNode, source: String): Int {
        val number = list.findFirst(MarkdownTokenTypes.LIST_NUMBER) ?: return 1
        return text(number, source).trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 1
    }

    private fun listItems(list: ASTNode, source: String): List<List<MarkdownInline>> {
        val items = mutableListOf<List<MarkdownInline>>()
        collectListItems(list, source, items)
        return items
    }

    private fun collectListItems(list: ASTNode, source: String, out: MutableList<List<MarkdownInline>>) {
        for (child in list.children) {
            when (child.type) {
                MarkdownElementTypes.LIST_ITEM -> collectItem(child, source, out)
                // Вложенный список на уровне списка встречается редко, но обходим и его.
                MarkdownElementTypes.UNORDERED_LIST,
                MarkdownElementTypes.ORDERED_LIST -> collectListItems(child, source, out)
                else -> Unit
            }
        }
    }

    private fun collectItem(item: ASTNode, source: String, out: MutableList<List<MarkdownInline>>) {
        val own = mutableListOf<MarkdownInline>()
        for (child in item.children) {
            when (child.type) {
                MarkdownElementTypes.PARAGRAPH -> {
                    if (own.isNotEmpty()) own += MarkdownInline.Text("\n")
                    own += flattenInline(child, source)
                }
                MarkdownElementTypes.CODE_FENCE -> {
                    val code = fencedCode(child, source)
                    if (own.isNotEmpty()) own += MarkdownInline.Text("\n")
                    own += MarkdownInline.Code(code.code)
                }
                MarkdownElementTypes.CODE_BLOCK -> {
                    val code = indentedCode(child, source)
                    if (own.isNotEmpty()) own += MarkdownInline.Text("\n")
                    own += MarkdownInline.Code(code.code)
                }
                MarkdownElementTypes.BLOCK_QUOTE -> {
                    val quoted = blocksToInlines(parseBlocks(child, source))
                    if (quoted.isNotEmpty()) {
                        if (own.isNotEmpty()) own += MarkdownInline.Text("\n")
                        own += quoted
                    }
                }
                else -> Unit
            }
        }
        normalize(own).takeIf { it.isNotEmpty() }?.let { out += it }

        // Вложенные списки идут следом за текстом пункта — так порядок чтения сохраняется.
        for (child in item.children) {
            if (child.type == MarkdownElementTypes.UNORDERED_LIST || child.type == MarkdownElementTypes.ORDERED_LIST) {
                collectListItems(child, source, out)
            }
        }
    }

    private fun blocksToInlines(blocks: List<MarkdownBlock>): List<MarkdownInline> =
        blocks.flatMapIndexed { index, block ->
            val inlines = when (block) {
                is MarkdownBlock.Paragraph -> block.inlines
                is MarkdownBlock.Heading -> block.inlines
                is MarkdownBlock.BulletList -> joinItems(block.items)
                is MarkdownBlock.OrderedList -> joinItems(block.items)
                is MarkdownBlock.Quote -> blocksToInlines(block.blocks)
                is MarkdownBlock.CodeBlock -> listOf(MarkdownInline.Code(block.code))
                MarkdownBlock.Divider -> emptyList()
            }
            if (index == 0 || inlines.isEmpty()) inlines else listOf(MarkdownInline.Text("\n")) + inlines
        }

    private fun joinItems(items: List<List<MarkdownInline>>): List<MarkdownInline> =
        items.flatMapIndexed { index, item ->
            if (index == 0) item else listOf(MarkdownInline.Text("\n")) + item
        }

    private fun fencedCode(node: ASTNode, source: String): MarkdownBlock.CodeBlock {
        var language: String? = null
        val code = StringBuilder()
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.FENCE_LANG -> {
                    if (language.isNullOrEmpty()) {
                        language = text(child, source).trim().ifEmpty { null }
                    }
                }
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> code.append(text(child, source)).append('\n')
                // Ограждения и переводы строк — служебные, в текст кода не входят.
                else -> Unit
            }
        }
        return MarkdownBlock.CodeBlock(language, dedent(code.toString().trimEnd('\n')))
    }

    private fun indentedCode(node: ASTNode, source: String): MarkdownBlock.CodeBlock {
        val lines = mutableListOf<String>()
        for (child in node.children) {
            if (child.type == MarkdownTokenTypes.CODE_LINE) lines += text(child, source)
        }
        return MarkdownBlock.CodeBlock(language = null, code = dedent(lines.joinToString("\n")))
    }

    /** Снимает общий отступ строк — код внутри списка не должен «уезжать» вправо. */
    private fun dedent(code: String): String {
        if (code.isEmpty()) return code
        val lines = code.split('\n')
        val minIndent = lines.filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it == ' ' || it == '\t' }.length }
            .minOrNull()
            ?: return code
        if (minIndent == 0) return code
        return lines.joinToString("\n") { line -> line.drop(minIndent.coerceAtMost(line.length)) }
    }

    // --- Инлайн-элементы ---

    /** Служебные токены, которые не являются текстом в инлайн-контексте. */
    private val structuralTokens: Set<IElementType> = setOf(
        MarkdownTokenTypes.ATX_HEADER,
        MarkdownTokenTypes.SETEXT_1,
        MarkdownTokenTypes.SETEXT_2,
        MarkdownTokenTypes.LIST_BULLET,
        MarkdownTokenTypes.LIST_NUMBER,
        MarkdownTokenTypes.BLOCK_QUOTE,
        MarkdownTokenTypes.FENCE_LANG,
        MarkdownTokenTypes.CODE_FENCE_START,
        MarkdownTokenTypes.CODE_FENCE_CONTENT,
        MarkdownTokenTypes.CODE_FENCE_END,
        GFMTokenTypes.TABLE_SEPARATOR,
    )

    private fun flattenInline(
        node: ASTNode,
        source: String,
        skip: Set<IElementType> = structuralTokens,
    ): List<MarkdownInline> {
        val result = mutableListOf<MarkdownInline>()
        for (child in node.children) {
            if (child.type in skip) continue
            when (child.type) {
                MarkdownTokenTypes.EOL,
                MarkdownTokenTypes.HARD_LINE_BREAK -> result += MarkdownInline.Text("\n")

                MarkdownElementTypes.STRONG -> result += MarkdownInline.Bold(
                    normalize(flattenInline(child, source, skip + MarkdownTokenTypes.EMPH)),
                )

                MarkdownElementTypes.EMPH -> result += MarkdownInline.Italic(
                    normalize(flattenInline(child, source, skip + MarkdownTokenTypes.EMPH)),
                )

                GFMElementTypes.STRIKETHROUGH -> result += MarkdownInline.Strikethrough(
                    normalize(flattenInline(child, source, skip + GFMTokenTypes.TILDE)),
                )

                MarkdownElementTypes.CODE_SPAN -> result += MarkdownInline.Code(codeSpanText(child, source))
                MarkdownElementTypes.INLINE_LINK -> result += inlineLink(child, source)
                MarkdownElementTypes.IMAGE -> result += MarkdownInline.Text(imageAlt(child, source))

                // Голые ссылки GFM (`https://…`) показываем кликабельными.
                GFMTokenTypes.GFM_AUTOLINK,
                MarkdownTokenTypes.AUTOLINK,
                MarkdownTokenTypes.URL -> {
                    val url = text(child, source).trim()
                    if (url.isNotEmpty()) result += MarkdownInline.Link(url, url)
                }

                // Контейнеры, внутри которых продолжается обычный инлайн-разбор.
                MarkdownTokenTypes.ATX_CONTENT,
                MarkdownTokenTypes.SETEXT_CONTENT,
                MarkdownElementTypes.LINK_LABEL -> result += flattenInline(child, source, skip)

                else -> {
                    // Неизвестная конструкция: сохраняем её исходный текст. Так ведут себя
                    // таблицы, HTML и «битые» ссылки вроде `[текст](` — ничего не теряется.
                    val raw = text(child, source)
                    if (raw.isNotEmpty()) result += MarkdownInline.Text(raw)
                }
            }
        }
        return result
    }

    private fun inlineLink(node: ASTNode, source: String): MarkdownInline {
        val textNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        val destinationNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
        val label = textNode?.let { plainText(it, source) }?.trim().orEmpty()
        val url = destinationNode?.let { text(it, source).trim().trim('<', '>') }.orEmpty()
        return if (url.isEmpty()) {
            MarkdownInline.Text(text(node, source))
        } else {
            MarkdownInline.Link(label.ifEmpty { url }, url)
        }
    }

    /** Alt картинки: саму картинку не рисуем, но подпись показываем текстом. */
    private fun imageAlt(node: ASTNode, source: String): String {
        val link = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
        val textNode = link?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
        val alt = textNode?.let { plainText(it, source) }?.trim()
        return if (alt.isNullOrEmpty()) text(node, source) else alt
    }

    private fun codeSpanText(node: ASTNode, source: String): String {
        val inner = text(node, source).trim('`')
        // CommonMark: если содержимое обрамлено пробелами и не состоит из них целиком,
        // по одному пробелу с каждого края отбрасывается.
        return if (inner.length > 2 && inner.startsWith(' ') && inner.endsWith(' ') && inner.isNotBlank()) {
            inner.substring(1, inner.length - 1)
        } else {
            inner
        }
    }

    private val plainTextSkip: Set<IElementType> = setOf(
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.EMPH,
        MarkdownTokenTypes.HARD_LINE_BREAK,
        GFMTokenTypes.TILDE,
    )

    /** Текст без разметки — для подписи ссылки и alt картинки. */
    private fun plainText(node: ASTNode, source: String): String {
        val builder = StringBuilder()
        for (child in node.children) {
            when {
                child.type in plainTextSkip -> Unit
                child.type == MarkdownTokenTypes.EOL -> builder.append(' ')
                child.children.isEmpty() -> builder.append(text(child, source))
                else -> builder.append(plainText(child, source))
            }
        }
        return builder.toString()
    }

    private fun text(node: ASTNode, source: String): String {
        val start = node.startOffset.coerceIn(0, source.length)
        val end = node.endOffset.coerceIn(start, source.length)
        return source.substring(start, end)
    }

    private fun ASTNode.findFirst(type: IElementType): ASTNode? {
        if (this.type == type) return this
        for (child in children) {
            child.findFirst(type)?.let { return it }
        }
        return null
    }

    // --- Нормализация текста ---

    /** Мягкие переносы: пробелы вокруг перевода строки в разметке не значимы. */
    private val softLineBreak = Regex("[ \\t]*\\n[ \\t]*")

    // Жёсткий перенос приходит двумя токенами (HARD_LINE_BREAK + EOL) — схлопываем,
    // иначе в абзаце появляется лишняя пустая строка.
    private val repeatedLineBreaks = Regex("\\n{2,}")

    private fun normalize(inlines: List<MarkdownInline>): List<MarkdownInline> {
        val merged = mutableListOf<MarkdownInline>()
        for (inline in inlines) {
            val previous = merged.lastOrNull()
            if (inline is MarkdownInline.Text && previous is MarkdownInline.Text) {
                merged[merged.lastIndex] = MarkdownInline.Text(previous.text + inline.text)
            } else {
                merged += inline
            }
        }
        val collapsed = merged.map { inline ->
            if (inline is MarkdownInline.Text) {
                val singleBreaks = softLineBreak.replace(inline.text, "\n")
                MarkdownInline.Text(repeatedLineBreaks.replace(singleBreaks, "\n"))
            } else {
                inline
            }
        }
        return trimEdges(collapsed)
    }

    /** Обрезает пробелы и переводы строк по краям, не трогая форматирование внутри. */
    private fun trimEdges(inlines: List<MarkdownInline>): List<MarkdownInline> {
        val result = inlines.toMutableList()
        while (result.isNotEmpty()) {
            val first = result.first()
            if (first !is MarkdownInline.Text) break
            val trimmed = first.text.trimStart()
            if (trimmed.isEmpty()) {
                result.removeAt(0)
            } else {
                if (trimmed.length != first.text.length) result[0] = MarkdownInline.Text(trimmed)
                break
            }
        }
        while (result.isNotEmpty()) {
            val last = result.last()
            if (last !is MarkdownInline.Text) break
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) {
                result.removeAt(result.lastIndex)
            } else {
                if (trimmed.length != last.text.length) result[result.lastIndex] = MarkdownInline.Text(trimmed)
                break
            }
        }
        return result
    }
}
