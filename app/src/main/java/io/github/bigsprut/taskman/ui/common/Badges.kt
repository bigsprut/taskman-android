package io.github.bigsprut.taskman.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bigsprut.taskman.R
import io.github.bigsprut.taskman.domain.Tag
import io.github.bigsprut.taskman.domain.TaskStatus

/**
 * Бейджи строки списка (ТЗ §5.3-Ф1: статуса, тегов, вложений, сотрудника).
 *
 * Цветной текст на залитом фоне берёт контрастный цвет по яркости фона — см. [contrastOn].
 */

@Composable
fun StatusBadge(status: TaskStatus, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(status.labelRes()),
        style = MaterialTheme.typography.labelMedium,
        color = status.labelColor(),
        maxLines = 1,
        modifier = modifier,
    )
}

/** Чип тега: заливка цветом тега с сервера, текст — авто-контраст. */
@Composable
fun TagChip(tag: Tag, modifier: Modifier = Modifier) {
    val background = parseTaskmanColor(
        raw = tag.color,
        fallback = MaterialTheme.colorScheme.secondaryContainer,
    )
    val textColor = if (tag.color == null) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        contrastOn(background)
    }
    Text(
        text = tag.title,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** счётчик вложений: иконка + число. */
@Composable
fun AttachmentBadge(count: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.badge_attachments, count)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_attachment),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Точка категории; цвет — с сервера, при мусоре — цвет темы. */
@Composable
fun CategoryDot(colorHex: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(parseTaskmanColor(colorHex, Color.Transparent)),
    )
}
