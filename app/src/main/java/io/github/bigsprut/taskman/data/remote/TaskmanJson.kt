package io.github.bigsprut.taskman.data.remote

import kotlinx.serialization.json.Json

/**
 * Единая конфигурация JSON для контракта taskman.
 *
 * - `ignoreUnknownKeys` — сервер может добавить поле, клиент не должен падать и терять данные;
 * - `explicitNulls = false` — отсутствующее и `null` значат одно и то же;
 * - `isLenient = false` — строгий разбор: кривой ответ должен стать явной ошибкой, а не
 *   молча превратиться в пустой список.
 */
val TaskmanJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = false
    encodeDefaults = true
}
