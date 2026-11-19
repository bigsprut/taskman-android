package io.github.bigsprut.taskman.data.repo

import com.google.common.truth.Truth.assertThat
import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.TaskStatusHistoryEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.data.remote.TaskmanJson
import io.github.bigsprut.taskman.data.remote.dto.ServerSettingsDto
import io.github.bigsprut.taskman.data.remote.dto.StateResponse
import io.github.bigsprut.taskman.testutil.attachmentDto
import io.github.bigsprut.taskman.testutil.historyDto
import io.github.bigsprut.taskman.testutil.stateResponse
import io.github.bigsprut.taskman.testutil.taskDto
import kotlinx.serialization.decodeFromString
import org.junit.Test

/**
 * Маппинг ответа `/api/state` в локальные сущности (ТЗ §6).
 */
class StateMapperTest {

    @Test
    fun `deleted 0 и 1 превращаются в boolean у задачи`() {
        val snapshot = StateMapper.toSnapshot(
            stateResponse(
                tasks = listOf(
                    taskDto(id = "active", deleted = 0),
                    taskDto(id = "deleted", deleted = 1),
                ),
            ),
        )

        assertThat(snapshot.tasks.first { it.id == "active" }.deleted).isFalse()
        assertThat(snapshot.tasks.first { it.id == "deleted" }.deleted).isTrue()
    }

    @Test
    fun `deleted 0 и 1 превращаются в boolean у вложения`() {
        val snapshot = StateMapper.toSnapshot(
            stateResponse(
                tasks = listOf(
                    taskDto(
                        id = "task-1",
                        attachments = listOf(
                            attachmentDto(id = "att-active", taskId = "task-1", deleted = 0),
                            attachmentDto(id = "att-deleted", taskId = "task-1", deleted = 1),
                        ),
                    ),
                ),
            ),
        )

        assertThat(snapshot.attachments.first { it.id == "att-active" }.deleted).isFalse()
        assertThat(snapshot.attachments.first { it.id == "att-deleted" }.deleted).isTrue()
    }

    @Test
    fun `statusDateTs берёт максимальный ts истории`() {
        val dto = taskDto(
            history = listOf(
                historyDto(status = "created", ts = 100.0),
                historyDto(status = "completed", ts = 300.0),
                historyDto(status = "inprogress", ts = 200.0),
            ),
            completedAt = 999.0,
            updatedAt = 998.0,
            createdAt = 997.0,
        )

        assertThat(StateMapper.statusDateTs(dto)).isEqualTo(300.0)
    }

    @Test
    fun `без истории statusDateTs берётся из completedAt`() {
        val dto = taskDto(completedAt = 50.0, updatedAt = 40.0, createdAt = 30.0)

        assertThat(StateMapper.statusDateTs(dto)).isEqualTo(50.0)
    }

    @Test
    fun `без истории и completedAt statusDateTs берётся из updatedAt`() {
        val dto = taskDto(updatedAt = 40.0, createdAt = 30.0)

        assertThat(StateMapper.statusDateTs(dto)).isEqualTo(40.0)
    }

    @Test
    fun `без истории completedAt и updatedAt statusDateTs берётся из createdAt`() {
        val dto = taskDto(createdAt = 30.0)

        assertThat(StateMapper.statusDateTs(dto)).isEqualTo(30.0)
    }

    @Test
    fun `без всех дат statusDateTs равен нулю`() {
        assertThat(StateMapper.statusDateTs(taskDto())).isEqualTo(0.0)
    }

    @Test
    fun `toSnapshot собирает связи, вложения и историю`() {
        val response = StateResponse(
            tasks = listOf(
                taskDto(
                    id = "task-1",
                    tags = listOf("tag-1", "tag-2"),
                    attachments = listOf(
                        attachmentDto(
                            id = "att-1",
                            taskId = "task-1",
                            origName = "file-1.txt",
                            mime = "text/plain",
                            size = 26,
                            createdAt = 11.0,
                        ),
                        // task_id не пришёл — вложение должно остаться у задачи из ответа.
                        attachmentDto(id = "att-2", taskId = null),
                    ),
                    history = listOf(
                        historyDto(status = "created", ts = 10.0),
                        historyDto(status = "completed", ts = 20.0),
                    ),
                ),
            ),
            settings = ServerSettingsDto(defaultCategoryId = "cat-1"),
        )

        val snapshot = StateMapper.toSnapshot(response)

        assertThat(snapshot.taskTags).containsExactly(
            TaskTagEntity(taskId = "task-1", tagId = "tag-1"),
            TaskTagEntity(taskId = "task-1", tagId = "tag-2"),
        )
        assertThat(snapshot.attachments).containsExactly(
            AttachmentEntity(
                id = "att-1",
                taskId = "task-1",
                origName = "file-1.txt",
                mime = "text/plain",
                size = 26,
                createdAt = 11.0,
                deleted = false,
            ),
            AttachmentEntity(
                id = "att-2",
                taskId = "task-1",
                origName = "file.txt",
                mime = null,
                size = 0,
                createdAt = 0.0,
                deleted = false,
            ),
        )
        assertThat(snapshot.history).containsExactly(
            TaskStatusHistoryEntity(taskId = "task-1", status = "created", ts = 10.0),
            TaskStatusHistoryEntity(taskId = "task-1", status = "completed", ts = 20.0),
        )
        assertThat(snapshot.defaultCategoryId).isEqualTo("cat-1")
    }

    @Test
    fun `снапшот фикстуры содержит все задачи, вложения и связи`() {
        val json = javaClass.getResourceAsStream("/api/state.sample.json")!!
            .use { it.readBytes() }
            .decodeToString()
        val snapshot = StateMapper.toSnapshot(TaskmanJson.decodeFromString<StateResponse>(json))

        assertThat(snapshot.tasks).hasSize(51)
        assertThat(snapshot.categories).hasSize(2)
        assertThat(snapshot.tags).hasSize(3)
        assertThat(snapshot.employees).hasSize(4)
        assertThat(snapshot.lists).hasSize(1)
        assertThat(snapshot.tasks.count { it.parentId != null }).isEqualTo(11)
        assertThat(snapshot.attachments).hasSize(4)
        // В фикстуре 21 задача с тегами, а связей 22: у одной задачи два тега.
        assertThat(snapshot.taskTags).hasSize(22)
        assertThat(snapshot.taskTags.map { it.taskId }.distinct()).hasSize(21)
        assertThat(snapshot.history.size).isGreaterThan(0)
        assertThat(snapshot.defaultCategoryId)
            .isEqualTo("ee374424b6d340a8b6fece6c6dee36a3")

        val first = snapshot.tasks.first { it.id == "340c5a71af6f48d389b6e8bd2ecaa8ff" }
        assertThat(first.statusDateTs).isEqualTo(1771275917.0)
        assertThat(first.deleted).isFalse()
    }
}
