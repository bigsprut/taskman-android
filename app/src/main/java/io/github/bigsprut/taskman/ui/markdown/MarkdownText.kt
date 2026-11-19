package io.github.bigsprut.taskman.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Отрисовка Markdown в стиле Material 3.
 *
 * Типографика берётся только из [MaterialTheme.typography], цвета — только из
 * [MaterialTheme.colorScheme], чтобы экран одинаково выглядел в светлой и тёмной теме
 * и не спорил с остальным приложением. Внешние отступы не добавляются: всё рисуется
 * строго внутри переданного [modifier].
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    // Разбор не бесплатный, а текст описания меняется редко — кэшируем по строке.
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(
        blocks = blocks,
        modifier = modifier,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> MarkdownBlockContent(block, contentColor) }
    }
}

@Composable
private fun MarkdownBlockContent(block: MarkdownBlock, contentColor: Color) {
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            text = inlineAnnotatedString(block.inlines),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )

        is MarkdownBlock.Heading -> Text(
            text = inlineAnnotatedString(block.inlines),
            style = headingTextStyle(block.level),
            color = contentColor,
        )

        is MarkdownBlock.BulletList -> BulletListBlock(block.items, contentColor)
        is MarkdownBlock.OrderedList -> OrderedListBlock(block.start, block.items, contentColor)
        is MarkdownBlock.Quote -> QuoteBlock(block.blocks)
        is MarkdownBlock.CodeBlock -> CodeBlockView(block)
        MarkdownBlock.Divider -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Заголовки 1–6: три верхних уровня заметно крупнее, 5–6 отличаются полужирным. */
@Composable
private fun headingTextStyle(level: Int): TextStyle {
    val typography = MaterialTheme.typography
    return when (level) {
        1 -> typography.headlineSmall
        2 -> typography.titleLarge
        3 -> typography.titleMedium
        4 -> typography.titleSmall
        else -> typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BulletListBlock(items: List<List<MarkdownInline>>, contentColor: Color) {
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            ListItemRow(marker = "•", item = item, contentColor = contentColor)
        }
    }
}

@Composable
private fun OrderedListBlock(start: Int, items: List<List<MarkdownInline>>, contentColor: Color) {
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { index, item ->
            ListItemRow(marker = "${start + index}.", item = item, contentColor = contentColor)
        }
    }
}

@Composable
private fun ListItemRow(marker: String, item: List<MarkdownInline>, contentColor: Color) {
    Row {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = inlineAnnotatedString(item),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(blocks: List<MarkdownBlock>) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        MarkdownBlocks(
            blocks = blocks,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        // Длинные строки кода не переносим, а прокручиваем по горизонтали.
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun inlineAnnotatedString(inlines: List<MarkdownInline>): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(inlines, linkColor, codeBackground, codeColor) {
        buildAnnotatedString { appendInlines(inlines, linkColor, codeBackground, codeColor) }
    }
}

private fun AnnotatedString.Builder.appendInlines(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    codeBackground: Color,
    codeColor: Color,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MarkdownInline.Text -> append(inline.text)

            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(inline.inlines, linkColor, codeBackground, codeColor)
            }

            is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(inline.inlines, linkColor, codeBackground, codeColor)
            }

            is MarkdownInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlines(inline.inlines, linkColor, codeBackground, codeColor)
            }

            is MarkdownInline.Code -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = codeColor,
                ),
            ) {
                append(inline.text)
            }

            // Кликабельность и оформление ссылки задаются одной аннотацией:
            // LinkAnnotation.Url сам открывает адрес через LocalUriHandler.
            is MarkdownInline.Link -> withLink(
                LinkAnnotation.Url(
                    url = inline.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                ),
            ) {
                append(inline.text)
            }
        }
    }
}
