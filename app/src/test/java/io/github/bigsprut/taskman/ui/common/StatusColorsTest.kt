package io.github.bigsprut.taskman.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.bigsprut.taskman.domain.StatusFilter
import io.github.bigsprut.taskman.domain.TaskStatus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Test

/**
 * Цветовая кодировка статусов (ТЗ §5.2): Выполнена — зелёный, Отменена — красный,
 * Активные (Создана + Выполняется) — жёлтый.
 *
 * Тесты держат то, что нельзя надёжно поймать глазами на скриншоте:
 * 1) маппинг статусов и фильтров на семейства — включая «Все» без цвета и общее семейство
 *    для «Создана» и «Выполняется»;
 * 2) читаемость — контраст текста на заливке по WCAG не ниже 4.5:1 в светлой и тёмной темах;
 * 3) семантику — зелёный/красный/жёлтый проверяются по соотношению каналов, а не по точному
 *    значению: палитру можно подкрутить (например, чуть приглушить), не переписывая тест.
 *
 * «Цвет — не единственный носитель» здесь не проверяется: текст статуса остаётся в UI, а
 * палитра только добавляет к нему цвет — это свойство экрана, а не чистой функции.
 *
 * Относительная яркость считается в тесте вручную по формуле WCAG, чтобы проверка контраста
 * не зависела от Android. Совпадение ручной формулы с `luminance()` из Compose проверяется
 * отдельным тестом — приложение выбирает контраст именно по нему.
 */
class StatusColorsTest {

    @Test
    fun `каждый статус задачи попадает в своё семейство цвета`() {
        val expected = mapOf(
            TaskStatus.CREATED to StatusColorFamily.ACTIVE,
            TaskStatus.IN_PROGRESS to StatusColorFamily.ACTIVE,
            TaskStatus.COMPLETED to StatusColorFamily.COMPLETED,
            TaskStatus.CANCELED to StatusColorFamily.CANCELED,
        )

        expected.forEach { (status, family) ->
            assertThat(status.colorFamily()).isEqualTo(family)
        }
    }

    @Test
    fun `созданная и выполняемая задачи красятся одинаково — активным семейством`() {
        // Требование §5.2: «Активные» — это и созданные, и задачи в работе. Разные цвета
        // у них сломали бы и легенду, и чип фильтра «Активные».
        assertThat(TaskStatus.CREATED.colorFamily())
            .isEqualTo(TaskStatus.IN_PROGRESS.colorFamily())
        assertThat(TaskStatus.CREATED.colorFamily()).isEqualTo(StatusColorFamily.ACTIVE)
    }

    @Test
    fun `все три семейства используются статусами`() {
        // Мёртвое семейство (цвет есть, статусов нет) — повод убрать его из палитры.
        assertThat(TaskStatus.entries.map { it.colorFamily() }.toSet())
            .containsExactly(
                StatusColorFamily.ACTIVE,
                StatusColorFamily.COMPLETED,
                StatusColorFamily.CANCELED,
            )
    }

    @Test
    fun `фильтры статусов красятся теми же семействами, что и задачи`() {
        assertThat(StatusFilter.ACTIVE.colorFamily()).isEqualTo(StatusColorFamily.ACTIVE)
        assertThat(StatusFilter.COMPLETED.colorFamily()).isEqualTo(StatusColorFamily.COMPLETED)
        assertThat(StatusFilter.CANCELED.colorFamily()).isEqualTo(StatusColorFamily.CANCELED)
    }

    @Test
    fun `фильтр все не имеет цветового семейства`() {
        // «Все» — не статус, а отсутствие среза: акцентный цвет здесь вводил бы в заблуждение.
        assertThat(StatusFilter.ALL.colorFamily()).isNull()
    }

    @Test
    fun `контраст текста на заливке не ниже 4 5 во всех семействах и обеих темах`() {
        combinations().forEach { (family, dark) ->
            val colors = statusColorsFor(family, dark)
            val ratio = contrastRatio(colors.content, colors.container)

            assertWithMessage("семейство $family, тёмная тема=$dark, ratio=$ratio")
                .that(ratio)
                .isAtLeast(WCAG_AA_TEXT)
        }
    }

    @Test
    fun `ручная формула яркости совпадает с luminance из Compose`() {
        // Контраст считаем сами, чтобы тест не зависел от Android, но формула должна давать
        // то же число, по которому приложение реально выбирает цвет текста.
        combinations().forEach { (family, dark) ->
            val colors = statusColorsFor(family, dark)
            listOf(colors.container, colors.content, colors.accent).forEach { color ->
                assertThat(relativeLuminance(color).toFloat())
                    .isWithin(LUMINANCE_TOLERANCE)
                    .of(color.luminance())
            }
        }
    }

    @Test
    fun `в светлой теме выполненные зелёные, отменённые красные, активные жёлтые`() {
        val active = statusColorsFor(StatusColorFamily.ACTIVE, dark = false).container
        val completed = statusColorsFor(StatusColorFamily.COMPLETED, dark = false).container
        val canceled = statusColorsFor(StatusColorFamily.CANCELED, dark = false).container

        assertThat(completed.isGreen()).isTrue()
        assertThat(canceled.isRed()).isTrue()
        assertThat(active.isYellow()).isTrue()

        // Плюс необратимая проверка пары «зелёный ≠ красный»: палитра не должна слипнуться.
        assertThat(completed).isNotEqualTo(canceled)
        assertThat(active).isNotEqualTo(completed)
        assertThat(active).isNotEqualTo(canceled)
    }

    @Test
    fun `в тёмной теме accent сохраняет цветовые отношения семейств`() {
        // В тёмной теме цвет чипа несёт именно accent (текст на нейтральном фоне): заливки
        // тёмные, и «зелёность» на глаз читается слабее. Поэтому семантику проверяем на нём.
        val active = statusColorsFor(StatusColorFamily.ACTIVE, dark = true).accent
        val completed = statusColorsFor(StatusColorFamily.COMPLETED, dark = true).accent
        val canceled = statusColorsFor(StatusColorFamily.CANCELED, dark = true).accent

        assertThat(completed.isGreen()).isTrue()
        assertThat(canceled.isRed()).isTrue()
        assertThat(active.isYellow()).isTrue()
    }

    @Test
    fun `в тёмной теме заливки тоже сохраняют цветовые отношения`() {
        // Решение: проверяем и container, а не только accent. Иначе тёмную палитру можно
        // развести «в серость» (формально контраст есть, а цвет потерян) — тест это заметит.
        val active = statusColorsFor(StatusColorFamily.ACTIVE, dark = true).container
        val completed = statusColorsFor(StatusColorFamily.COMPLETED, dark = true).container
        val canceled = statusColorsFor(StatusColorFamily.CANCELED, dark = true).container

        assertThat(completed.isGreen()).isTrue()
        assertThat(canceled.isRed()).isTrue()
        assertThat(active.isYellow()).isTrue()
    }

    @Test
    fun `светлая и тёмная темы дают разные заливки для каждого семейства`() {
        StatusColorFamily.entries.forEach { family ->
            val light = statusColorsFor(family, dark = false)
            val dark = statusColorsFor(family, dark = true)

            assertThat(dark.container).isNotEqualTo(light.container)
        }
    }

    @Test
    fun `текст на заливке — только тёмный или светлый из палитры M3`() {
        combinations().forEach { (family, dark) ->
            val colors = statusColorsFor(family, dark)

            // Ровно два цвета текста: «авто-контраст» — это выбор из двух, а не подбор оттенка.
            assertThat(colors.content).isAnyOf(DARK_TEXT, LIGHT_TEXT)
            // Текст не растворяется в фоне.
            assertThat(colors.content).isNotEqualTo(colors.container)
        }
    }

    @Test
    fun `светлые заливки получают тёмный текст, тёмные — белый`() {
        combinations().forEach { (family, dark) ->
            val colors = statusColorsFor(family, dark)

            // Палитра подобрана так: светлая тема — пастельные заливки под тёмный текст,
            // тёмная — насыщенные тёмные заливки под белый. Направление фиксируем отдельно,
            // чтобы смена порога контраста не прошла незамеченной.
            assertThat(colors.content).isEqualTo(if (dark) LIGHT_TEXT else DARK_TEXT)
        }
    }

    @Test
    fun `три семейства дают разные заливки в обеих темах`() {
        listOf(false, true).forEach { dark ->
            val containers = StatusColorFamily.entries.map { statusColorsFor(it, dark).container }

            assertWithMessage("тёмная тема=$dark")
                .that(containers.toSet())
                .hasSize(3)
        }
    }

    /** Все пары «семейство × тема»: светлая и тёмная. */
    private fun combinations(): List<Pair<StatusColorFamily, Boolean>> =
        StatusColorFamily.entries.flatMap { family ->
            listOf(false, true).map { dark -> family to dark }
        }

    /**
     * Относительная яркость по WCAG 2.x: каналы sRGB нормализуются, линеаризуются обратной
     * гамма-функцией и складываются весами 0.2126/0.7152/0.0722.
     *
     * Считаем в `Double` и без Android — тест контраста остаётся обычным JVM-тестом.
     */
    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    /** Коэффициент контраста по WCAG: `(Lmax + 0.05) / (Lmin + 0.05)`. */
    private fun contrastRatio(first: Color, second: Color): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** «Зелёный»: зелёный канал заметно выше и красного, и синего. */
    private fun Color.isGreen(): Boolean = green > red && green > blue

    /** «Красный»: красный канал заметно выше и зелёного, и синего. */
    private fun Color.isRed(): Boolean = red > green && red > blue

    /**
     * «Жёлтый»: красный и зелёный близки друг к другу и оба заметно выше синего.
     *
     * Пороги грубые, с запасом: у палитры разница красного и зелёного ≤ 0.17, а отрыв от
     * синего ≥ 0.29. Главное — не дать перекрасить «Активные» в оранжевый или в серый.
     */
    private fun Color.isYellow(): Boolean =
        abs(red - green) <= YELLOW_CHANNEL_TOLERANCE && min(red, green) - blue >= YELLOW_BLUE_GAP

    private companion object {
        /** Тёмный текст M3 (`onSurface`) — он же тёмный вариант [contrastOn]. */
        val DARK_TEXT: Color = Color(0xFF1C1B1F)

        /** Белый текст для тёмных заливок. */
        val LIGHT_TEXT: Color = Color(0xFFFFFFFF)

        /** Порог WCAG AA для обычного (не крупного) текста. */
        const val WCAG_AA_TEXT = 4.5

        /** Допуск сверки самописной яркости с Compose: расхождением считаем больше 0.0001. */
        const val LUMINANCE_TOLERANCE = 0.0001f

        /** Насколько красный и зелёный могут расходиться, оставаясь «жёлтым». */
        const val YELLOW_CHANNEL_TOLERANCE = 0.2f

        /** Минимальный отрыв жёлтого от синего канала. */
        const val YELLOW_BLUE_GAP = 0.2f
    }
}
