package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.TaskStatus

/**
 * Цветовая кодировка статусов (ТЗ §5.2, решение владельца 20.11):
 * Выполнена — зелёный, Отменена — красный, Активные (Создана + Выполняется) — жёлтый.
 *
 * Палитра одна на весь UI: бейдж статуса на карточке, чипы фильтра статусов и их счётчики.
 * Цвет — не единственный носитель: текст статуса остаётся всегда (§5.2), а цвет текста на
 * заливке выбирается автоматически по яркости фона ([contrastOn]), поэтому палитра читаема
 * и в светлой, и в тёмной теме.
 *
 * Функции выбора цвета — чистые (`statusColorsFor`), чтобы контраст можно было проверить
 * JVM-тестом без Compose.
 */

/** Семейство статуса для цветокодировки. */
enum class StatusColorFamily { ACTIVE, COMPLETED, CANCELED }

/** Статус задачи → семейство цвета. */
fun TaskStatus.colorFamily(): StatusColorFamily = when (this) {
    TaskStatus.CREATED, TaskStatus.IN_PROGRESS -> StatusColorFamily.ACTIVE
    TaskStatus.COMPLETED -> StatusColorFamily.COMPLETED
    TaskStatus.CANCELED -> StatusColorFamily.CANCELED
}

/** Фильтр статуса → семейство цвета; «Все» — нейтральный, без акцента. */
fun StatusFilter.colorFamily(): StatusColorFamily? = when (this) {
    StatusFilter.ACTIVE -> StatusColorFamily.ACTIVE
    StatusFilter.COMPLETED -> StatusColorFamily.COMPLETED
    StatusFilter.CANCELED -> StatusColorFamily.CANCELED
    StatusFilter.ALL -> null
}

/**
 * Палитра семейства.
 *
 * @property container фон бейджа/выбранного чипа.
 * @property content цвет текста на [container] (авто-контраст).
 * @property accent цвет текста невыбранного чипа на нейтральном фоне.
 */
data class StatusColors(
    val container: Color,
    val content: Color,
    val accent: Color,
)

/** Палитра для темы. Светлая — пастельные заливки, тёмная — насыщенные тёмные. */
fun statusColorsFor(family: StatusColorFamily, dark: Boolean): StatusColors {
    val container = if (dark) darkContainer(family) else lightContainer(family)
    return StatusColors(
        container = container,
        content = contrastOn(container),
        accent = if (dark) darkAccent(family) else lightAccent(family),
    )
}

private fun lightContainer(family: StatusColorFamily): Color = when (family) {
    StatusColorFamily.ACTIVE -> Color(0xFFF7E08A)
    StatusColorFamily.COMPLETED -> Color(0xFFB7E4C7)
    StatusColorFamily.CANCELED -> Color(0xFFF5B7B1)
}

private fun darkContainer(family: StatusColorFamily): Color = when (family) {
    StatusColorFamily.ACTIVE -> Color(0xFF5C4A00)
    StatusColorFamily.COMPLETED -> Color(0xFF2E5B3C)
    StatusColorFamily.CANCELED -> Color(0xFF6E2B2B)
}

private fun lightAccent(family: StatusColorFamily): Color = when (family) {
    StatusColorFamily.ACTIVE -> Color(0xFF8A6D00)
    StatusColorFamily.COMPLETED -> Color(0xFF2E7D32)
    StatusColorFamily.CANCELED -> Color(0xFFC62828)
}

private fun darkAccent(family: StatusColorFamily): Color = when (family) {
    StatusColorFamily.ACTIVE -> Color(0xFFFFD54F)
    StatusColorFamily.COMPLETED -> Color(0xFF7BD88F)
    StatusColorFamily.CANCELED -> Color(0xFFFF8A80)
}
