package io.github.bigsprut.taskman.data.export

import io.github.bigsprut.taskman.data.local.entity.AttachmentEntity
import io.github.bigsprut.taskman.data.local.entity.CategoryEntity
import io.github.bigsprut.taskman.data.local.entity.TagEntity
import io.github.bigsprut.taskman.data.local.entity.TaskEntity
import io.github.bigsprut.taskman.data.local.entity.TaskTagEntity
import io.github.bigsprut.taskman.domain.TaskStatus

/** Всё, что нужно для экспорта: снапшот + справочники (см. [MarkdownExport]). */
data class ExportSnapshot(
    val tasks: List<TaskEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val attachments: List<AttachmentEntity> = emptyList(),
    val taskTags: List<TaskTagEntity> = emptyList(),
)

/**
 * Экспорт задач в markdown (§5.3-Ф5, критерий §9-Ф5: «корректный markdown по фикстуре»).
 *
 * Формат (стабильный, проверяется тестом):
 * ```
 * # Задачи
 *
 * _Экспорт: <дата>_
 *
 * ## Категория 1
 *
 * - [ ] Задача №43 (тестовая)
 *   - статус: В работе
 *   - теги: Ter 3
 *   - сотрудник: Сотрудник 2
 *   - вложений: 1
 *   - создана: 30.10.2026 09:00
 *   - описание: текст в одну строку
 *   - подзадачи:
 *     - [x] подзадача
 *
 * ## Без категории
 * ```
 *
 * Строки статусов и даты приходят снаружи ([statusLabel], [formatDate]): так данные не тянут
 * за собой Android-ресурсы и форматирование, а экспорт остаётся чистой функцией, которую можно
 * проверить на JVM и на фикстуре.
 */
object MarkdownExport {

    /** Порядок статусов при экспорте — как в списке (§5.3-Ф1, `STATUS_ORDER`). */
    private val taskOrder: Comparator<TaskEntity> = compareBy<TaskEntity> {
        TaskStatus.fromWireValue(it.status).sortIndex
    }.thenByDescending { it.statusDateTs }.thenBy { it.id }

    fun build(
        snapshot: ExportSnapshot,
        generatedAtLabel: String,
        statusLabel: (String) -> String,
        formatDate: (Double) -> String,
    ): String {
        val activeTasks = snapshot.tasks.filterNot { it.deleted }
        val tasksById = activeTasks.associateBy { it.id }
        val childrenByParent = activeTasks
            .filter { it.parentId != null && tasksById.containsKey(it.parentId) }
            .groupBy { it.parentId.orEmpty() }
            .mapValues { (_, children) -> children.sortedWith(taskOrder) }

        // Корень — задача без родителя ИЛИ с родителем, которого нет в снапшоте (осиротевшая
        // подзадача не должна потеряться при экспорте).
        val roots = activeTasks
            .filter { it.parentId == null || !tasksById.containsKey(it.parentId) }
            .sortedWith(taskOrder)

        val tagsById = snapshot.tags.associateBy { it.id }
        val tagIdsByTask = snapshot.taskTags.groupBy({ it.taskId }, { it.tagId })
        val attachmentCountByTask = snapshot.attachments
            .filterNot { it.deleted }
            .groupingBy { it.taskId }
            .eachCount()

        val builder = StringBuilder()
        builder.appendLine("# Задачи")
        builder.appendLine()
        builder.append("_Экспорт: ").append(generatedAtLabel).append('_')
        builder.appendLine()
        builder.appendLine()

        val categories = snapshot.categories.sortedBy { it.position }
        categories.forEach { category ->
            val categoryRoots = roots.filter { it.categoryId == category.id }
            if (categoryRoots.isEmpty()) return@forEach
            builder.append("## ").append(category.title).appendLine()
            builder.appendLine()
            categoryRoots.forEach { task ->
                appendTask(
                    builder = builder,
                    task = task,
                    children = childrenByParent[task.id].orEmpty(),
                    tags = tagIdsByTask[task.id].orEmpty().mapNotNull { tagsById[it]?.title },
                    attachmentCount = attachmentCountByTask[task.id] ?: 0,
                    statusLabel = statusLabel,
                    formatDate = formatDate,
                )
            }
        }

        val knownCategoryIds = categories.mapTo(mutableSetOf()) { it.id }
        val uncategorized = roots.filter { it.categoryId == null || it.categoryId !in knownCategoryIds }
        if (uncategorized.isNotEmpty()) {
            builder.append("## Без категории").appendLine()
            builder.appendLine()
            uncategorized.forEach { task ->
                appendTask(
                    builder = builder,
                    task = task,
                    children = childrenByParent[task.id].orEmpty(),
                    tags = tagIdsByTask[task.id].orEmpty().mapNotNull { tagsById[it]?.title },
                    attachmentCount = attachmentCountByTask[task.id] ?: 0,
                    statusLabel = statusLabel,
                    formatDate = formatDate,
                )
            }
        }

        return builder.toString().trimEnd() + "\n"
    }

    private fun appendTask(
        builder: StringBuilder,
        task: TaskEntity,
        children: List<TaskEntity>,
        tags: List<String>,
        attachmentCount: Int,
        statusLabel: (String) -> String,
        formatDate: (Double) -> String,
    ) {
        builder.append(if (task.isCompleted()) "- [x] " else "- [ ] ")
            .append(task.title).appendLine()
        builder.append("  - статус: ").append(statusLabel(task.status)).appendLine()
        if (tags.isNotEmpty()) {
            builder.append("  - теги: ").append(tags.joinToString(", ")).appendLine()
        }
        task.source?.takeIf { it.isNotBlank() }?.let { source ->
            builder.append("  - сотрудник: ").append(source).appendLine()
        }
        if (attachmentCount > 0) {
            builder.append("  - вложений: ").append(attachmentCount).appendLine()
        }
        task.createdAt?.let { createdAt ->
            builder.append("  - создана: ").append(formatDate(createdAt)).appendLine()
        }
        val content = task.content.trim()
        if (content.isNotEmpty()) {
            // Многострочное описание сворачиваем в одну строку: экспорт — читаемый список, а не
            // копия документа.
            builder.append("  - описание: ")
                .append(content.replace("\r\n", "\n").replace('\n', ' '))
                .appendLine()
        }
        if (children.isNotEmpty()) {
            builder.append("  - подзадачи:").appendLine()
            children.forEach { child ->
                builder.append("    - ")
                    .append(if (child.isCompleted()) "[x] " else "[ ] ")
                    .append(child.title)
                    .append(" — ").append(statusLabel(child.status))
                    .appendLine()
            }
        }
        builder.appendLine()
    }

    private fun TaskEntity.isCompleted(): Boolean = TaskStatus.fromWireValue(status) == TaskStatus.COMPLETED
}
