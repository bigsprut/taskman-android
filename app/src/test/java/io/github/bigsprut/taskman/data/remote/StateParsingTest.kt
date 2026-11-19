package io.github.bigsprut.taskman.data.remote

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.remote.dto.ApiErrorDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import kotlinx.serialization.decodeFromString
import org.junit.Test

/**
 * Разбор эталонной фикстуры `/api/state` (ТЗ §6).
 *
 * Фикстура подключена к тестовым ресурсам в `app/build.gradle.kts`
 * (`fixtures/` → `test.resources`), поэтому читается по пути `/api/state.sample.json`.
 */
class StateParsingTest {

    private val stateJson: String by lazy {
        javaClass.getResourceAsStream("/api/state.sample.json")!!
            .use { it.readBytes() }
            .decodeToString()
    }

    private fun state(): StateResponse = TaskmanJson.decodeFromString<StateResponse>(stateJson)

    @Test
    fun `фикстура разбирается в ожидаемые секции`() {
        val response = state()

        assertThat(response.tasks).hasSize(51)
        assertThat(response.categories).hasSize(2)
        assertThat(response.tags).hasSize(3)
        assertThat(response.employees).hasSize(4)
        assertThat(response.lists).hasSize(1)
    }

    @Test
    fun `настройки и время сервера читаются из фикстуры`() {
        val response = state()

        assertThat(response.settings.defaultCategoryId)
            .isEqualTo("ee374424b6d340a8b6fece6c6dee36a3")
        assertThat(response.serverTime).isNotNull()
    }

    @Test
    fun `первая задача сохраняет поля контракта`() {
        val task = state().tasks.first { it.id == "340c5a71af6f48d389b6e8bd2ecaa8ff" }

        assertThat(task.title).isEqualTo("Задача №1 (тестовая)")
        assertThat(task.status).isEqualTo("created")
        assertThat(task.source).isEqualTo("Сотрудник 2")
        assertThat(task.tags).hasSize(1)
        assertThat(task.attachments).hasSize(1)
        assertThat(task.history).hasSize(1)

        val attachment = task.attachments.single()
        assertThat(attachment.origName).isEqualTo("file-1.txt")
        assertThat(attachment.mime).isEqualTo("text/plain")
        assertThat(attachment.size).isEqualTo(26L)
    }

    @Test
    fun `статусы в фикстуре распределены ожидаемо`() {
        val byStatus = state().tasks.groupingBy { it.status }.eachCount()

        assertThat(byStatus).hasSize(3)
        assertThat(byStatus.getValue("created")).isEqualTo(29)
        assertThat(byStatus.getValue("completed")).isEqualTo(19)
        assertThat(byStatus.getValue("inprogress")).isEqualTo(3)
    }

    @Test
    fun `неизвестное поле не мешает разбору`() {
        val json = """
            {
              "extra": 1,
              "tasks": [
                {"id": "task-1", "title": "Задача", "status": "created",
                 "extra": 1, "history": [{"status": "created", "ts": 10.0, "extra": true}]}
              ]
            }
        """.trimIndent()

        val response = TaskmanJson.decodeFromString<StateResponse>(json)

        assertThat(response.tasks).hasSize(1)
        assertThat(response.tasks.single().title).isEqualTo("Задача")
        assertThat(response.tasks.single().history).hasSize(1)
    }

    @Test
    fun `тело ошибки конфликта разбирается`() {
        val dto = TaskmanJson.decodeFromString<ApiErrorDto>(
            """{"error":"conflict","actual_updated_at":1765843200.123}""",
        )

        assertThat(dto.error).isEqualTo("conflict")
        assertThat(dto.actualUpdatedAt).isEqualTo(1765843200.123)
    }
}
