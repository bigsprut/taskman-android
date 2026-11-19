package io.github.bigsprut.taskman.data.export

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.data.repo.StateMapper
import io.github.bigsprut.taskman.testutil.taskEntity
import kotlinx.serialization.decodeFromString
import org.junit.Test

/**
 * Экспорт в markdown (§5.3-Ф5, критерий §9-Ф5 «корректный markdown по фикстуре»).
 *
 * Главная проверка — прогон на настоящем ответе `/api/state` из фикстуры: все активные
 * корневые задачи попадают в файл, выполненные отмечены, подзадачи вложены с отступом,
 * справочники (категории, теги, сотрудники, вложения) подставлены из снапшота.
 *
 * Строки статусов и даты приходят лямбдами: в тесте это константы, поэтому проверяется
 * ровно формат, а не локализация. Синтетические снапшоты нужны там, где фикстура не даёт
 * нужного случая (удалённые задачи, порядок категорий, «Без категории»).
 */
class MarkdownExportTest {

    private val fixture: ExportSnapshot by lazy { loadFixture() }

    private val markdown: String by lazy { build(fixture) }

    // --- Фикстура ---

    @Test
    fun `экспорт начинается с заголовка и пустой строки`() {
        val lines = markdown.lines()

        assertThat(lines[0]).isEqualTo("# Задачи")
        assertThat(lines[1]).isEmpty()
        assertThat(lines[2]).isEqualTo("_Экспорт: ${GENERATED_AT}_")
    }

    @Test
    fun `все корневые активные задачи фикстуры присутствуют`() {
        val roots = fixture.tasks.filterNot { it.deleted }.filter { it.parentId == null }
        assertThat(roots).hasSize(40)

        val topLevel = markdown.lines().filter { it.startsWith("- [") }
        assertThat(topLevel).hasSize(roots.size)
        roots.forEach { root ->
            assertThat(topLevel.any { it.endsWith(root.title) }).isTrue()
        }
    }

    @Test
    fun `выполненные отмечены крестиком, остальные пустым чекбоксом`() {
        val lines = markdown.lines()

        assertThat(lines.count { it.startsWith("- [x] ") }).isEqualTo(16)
        assertThat(lines.count { it.startsWith("- [ ] ") }).isEqualTo(24)
        assertThat(markdown).contains("- [x] Задача №6 (тестовая)\n")
        assertThat(markdown).contains("- [ ] Задача №1 (тестовая)\n")
    }

    @Test
    fun `подзадачи печатаются с отступом в четыре пробела`() {
        val children = markdown.lines().filter { it.startsWith("    - [") }

        assertThat(children).hasSize(11)
        assertThat(children).contains("    - [ ] Задача №30 (тестовая) — created")
        assertThat(children).contains("    - [x] Задача №40 (тестовая) — completed")
        // Подзадача не должна всплыть как задача верхнего уровня.
        assertThat(markdown.lines()).doesNotContain("- [ ] Задача №30 (тестовая)")
    }

    @Test
    fun `категория фикстуры становится разделом`() {
        val sections = sections(markdown)

        // Все корневые задачи фикстуры лежат в «Категории 1»; «Категория 2» без задач
        // и поэтому не выводится, а «Без категории» не нужен вовсе.
        assertThat(sections.keys).containsExactly("Категория 1")
    }

    @Test
    fun `теги задачи печатаются названиями из справочника`() {
        assertThat(blockOf(markdown, "- [ ] Задача №1 (тестовая)"))
            .contains("  - теги: Тег 1")
        // У задачи №11 два тега — порядок как в фикстуре: Тег 1, Тег 2.
        assertThat(blockOf(markdown, "- [ ] Задача №11 (тестовая)"))
            .contains("  - теги: Тег 1, Тег 2")
    }

    @Test
    fun `сотрудник печатается из source`() {
        assertThat(blockOf(markdown, "- [ ] Задача №1 (тестовая)"))
            .contains("  - сотрудник: Сотрудник 2")
    }

    @Test
    fun `вложения считаются и печатаются числом`() {
        assertThat(blockOf(markdown, "- [ ] Задача №1 (тестовая)"))
            .contains("  - вложений: 1")
        assertThat(blockOf(markdown, "- [ ] Задача №11 (тестовая)"))
            .contains("  - вложений: 3")
        // Без вложений строки нет вовсе, а не «вложений: 0».
        assertThat(blockOf(markdown, "- [ ] Задача №2 (тестовая)").none { it.startsWith("  - вложений:") })
            .isTrue()
    }

    @Test
    fun `два вызова дают идентичный markdown`() {
        assertThat(build(fixture)).isEqualTo(build(fixture))
    }

    @Test
    fun `подпись экспорта и даты берутся из переданных лямбд`() {
        val custom = build(fixture, label = GENERATED_AT, formatDate = { DATE_LABEL })

        assertThat(custom.lines()[2]).isEqualTo("_Экспорт: ${GENERATED_AT}_")
        val rootsWithDate = fixture.tasks.count { !it.deleted && it.parentId == null && it.createdAt != null }
        assertThat(custom.lines().count { it == "  - создана: $DATE_LABEL" }).isEqualTo(rootsWithDate)
    }

    // --- Синтетические снапшоты ---

    @Test
    fun `удалённые задачи и вложения не попадают в экспорт`() {
        val snapshot = ExportSnapshot(
            tasks = listOf(
                taskEntity(id = "active", title = "Активная"),
                taskEntity(id = "deleted", title = "Удалённая", deleted = true),
                taskEntity(id = "live-child", parentId = "active", title = "Живая подзадача"),
                taskEntity(
                    id = "dead-child",
                    parentId = "active",
                    title = "Удалённая подзадача",
                    deleted = true,
                ),
            ),
            attachments = listOf(
                attachmentEntity(id = "att-live", taskId = "active", deleted = false),
                attachmentEntity(id = "att-dead", taskId = "active", deleted = true),
            ),
        )

        val result = build(snapshot)

        assertThat(result).contains("- [ ] Активная")
        assertThat(result).contains("    - [ ] Живая подзадача — created")
        assertThat(result).doesNotContain("Удалённая")
        assertThat(result).doesNotContain("Удалённая подзадача")
        // Удалённое вложение не считается: у задачи ровно одно живое.
        assertThat(result).contains("  - вложений: 1")
        assertThat(result).doesNotContain("вложений: 2")
    }

    @Test
    fun `категории идут по position, задачи без категории — отдельным разделом`() {
        val snapshot = ExportSnapshot(
            categories = listOf(
                CategoryEntity(id = "cat-2", title = "Вторая", color = null, position = 2),
                CategoryEntity(id = "cat-1", title = "Первая", color = null, position = 1),
            ),
            tasks = listOf(
                taskEntity(id = "task-b", title = "Из второй", categoryId = "cat-2"),
                taskEntity(id = "task-a", title = "Из первой", categoryId = "cat-1"),
                taskEntity(id = "task-none", title = "Без категории", categoryId = null),
                taskEntity(id = "task-lost", title = "Потерянная категория", categoryId = "cat-gone"),
            ),
        )

        val sections = sections(build(snapshot))

        assertThat(sections.keys).containsExactly("Первая", "Вторая", "Без категории").inOrder()
        assertThat(sections["Первая"]!!).contains("- [ ] Из первой")
        assertThat(sections["Вторая"]!!).contains("- [ ] Из второй")
        // Неизвестный id категории — тот же случай, что «без категории», а не потеря задачи.
        assertThat(sections["Без категории"]!!).contains("- [ ] Без категории")
        assertThat(sections["Без категории"]!!).contains("- [ ] Потерянная категория")
    }

    @Test
    fun `пустой снапшот даёт только заголовок и подпись`() {
        assertThat(build(ExportSnapshot()))
            .isEqualTo("# Задачи\n\n_Экспорт: ${GENERATED_AT}_\n")
    }

    // --- Вспомогательное ---

    /** Загружает снапшот фикстуры так же, как сетевые тесты: через маппер ответа `/api/state`. */
    private fun loadFixture(): ExportSnapshot {
        val json = javaClass.getResourceAsStream(FIXTURE)!!
            .use { it.readBytes() }
            .decodeToString()
        val state = StateMapper.toSnapshot(TaskmanJson.decodeFromString<StateResponse>(json))
        return ExportSnapshot(
            tasks = state.tasks,
            tags = state.tags,
            categories = state.categories,
            attachments = state.attachments,
            taskTags = state.taskTags,
        )
    }

    private fun build(
        snapshot: ExportSnapshot,
        label: String = GENERATED_AT,
        statusLabel: (String) -> String = { it },
        formatDate: (Double) -> String = { DATE_LABEL },
    ): String = MarkdownExport.build(snapshot, label, statusLabel, formatDate)

    /** Строки блока задачи: всё после её чекбокса до первой пустой строки. */
    private fun blockOf(markdown: String, checkboxLine: String): List<String> {
        val lines = markdown.lines()
        val start = lines.indexOf(checkboxLine)
        assertThat(start).isAtLeast(0)
        return lines.drop(start + 1).takeWhile { it.isNotEmpty() }
    }

    /** Разделы `## …` в порядке вывода: так проверяются и состав, и порядок категорий. */
    private fun sections(markdown: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var current: MutableList<String>? = null
        markdown.lines().forEach { line ->
            if (line.startsWith("## ")) {
                val section = mutableListOf<String>()
                result[line.removePrefix("## ")] = section
                current = section
            } else {
                current?.add(line)
            }
        }
        return result
    }

    private fun attachmentEntity(
        id: String,
        taskId: String,
        deleted: Boolean,
    ): AttachmentEntity = AttachmentEntity(
        id = id,
        taskId = taskId,
        origName = "file.txt",
        mime = null,
        size = 1,
        createdAt = 0.0,
        deleted = deleted,
    )

    private companion object {
        /** Фикстура API лежит в корне репозитория и подключена как ресурс тестов. */
        const val FIXTURE = "/api/state.sample.json"

        /** Подпись «сгенерировано»: константа, чтобы результат был детерминированным. */
        const val GENERATED_AT = "20.11.2026 01:00"

        /** Подменная дата — проверяется подстановка, а не форматирование. */
        const val DATE_LABEL = "ДАТА"
    }
}
