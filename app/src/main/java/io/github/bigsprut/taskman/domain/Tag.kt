package io.github.bigsprut.taskman.domain

/**
 * Тег задачи в терминах домена: нужен для чипов фильтра и бейджей (§5.3-Ф1).
 * @property color `#RRGGBB` с сервера; может отсутствовать.
 */
data class Tag(
    val id: String,
    val title: String,
    val color: String?,
)

/**
 * Категория задачи: чипы фильтра по категориям, цветная точка в строке списка.
 * @property color `#RRGGBB` с сервера; может отсутствовать.
 */
data class Category(
    val id: String,
    val title: String,
    val color: String?,
)
