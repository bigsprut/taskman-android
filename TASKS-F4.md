# TASKS Ф4 — карточки для исполнителей

Правила передачи и окружение — из `TASKS.md` (Ф0) и `TASKS-F1.md`…`TASKS-F3.md`.
Сборку и прогон тестов ведёт один процесс (ведущий).

## Статус карточек (итог Ф4)

| Карточка | Исполнитель | Статус | Итог |
|---|---|---|---|
| F4-01 Тесты офлайн-очереди | `tests-f4` | ✅ | 28 тестов: очередь, диспетчер, только-`flush`; обновил старые тесты под семантику `Queued` |
| F4-02 Схема и миграция | ведущий | ✅ | `pending_mutations`, Room v2 + `MIGRATION_1_2` (деструктивный fallback убран) |
| F4-03 Очередь и оптимистичное применение | ведущий | ✅ | `OfflineMutationQueue`, `LocalTaskWriter`/`DatabaseLocalTaskWriter`, upsert полей и soft-delete поддерева |
| F4-04 Диспетчер мутаций | ведущий | ✅ | `MutationOutcome.Queued`, правила сеть/4xx/409/401, отправка очереди |
| F4-05 WorkManager | ведущий | ✅ | `MutationSyncWorker` (сеть + backoff), `RefreshWorker` (15 мин), `SyncScheduler` |
| F4-06 UI | ведущий | ✅ | баннер, «сохранено локально», редактор закрывается после локального сохранения |
| F4-07 Q15 (мелкий коммит) | ведущий | ✅ | отдельное сообщение «нет доступа к файлу» + тест, отдельный коммит `2af67a9` |
| F4-08 Отчёт и PR | ведущий | ✅ | `PHASE-REPORT.md`, `QUESTIONS.md` Q18–Q19, техдолг по стримингу |

## Контракты Ф4 (для продолжения работы)

- `MutationOutcome` — `Success`, `Queued`, `Conflict(actualUpdatedAt)`, `Failed(failure)`.
- `OfflineMutationQueue(dao)` — `enqueueUpsert(payload, nowSeconds)` (схлопывание, версия от
  первой правки), `enqueueDelete`, `pending`, `remove`, `clear`, `observePendingCount`.
- `LocalTaskWriter` — `applyUpsert(entity)` / `applyDelete(taskId)`; Room-реализация —
  `DatabaseLocalTaskWriter`.
- `TaskMutationRepository(api, reader, tasks, queue, writer, now, requestBackgroundSync)` —
  `submit`-методы + `flush()`; `observePendingCount()` для баннера.
- `SyncScheduler` — `requestFlush()`, `schedulePeriodicSync()`, `observeNetworkAndFlush()`.

## Правила, добытые в Ф4

1. **`baseUpdatedAt` фиксируется при первой правке задачи** и подставляется в `updated_at`
   payload. Иначе после первой успешной отправки следующая получила бы ложный 409.
2. **В очередь — только `IOException`.** Любой 4xx снимает запись, перечитывает состояние и
   отдаётся в UI точной причиной. 409 — отдельный исход (`Conflict`).
3. **401/403 `device` запись не снимают** — отправка повторится после входа/подтверждения.
4. **Миграции обязательны**: деструктивный fallback удалил бы очередь. Новая таблица — только
   `CREATE TABLE`, данные снапшота не трогаем.
5. **Дубли не появляются** за счёт одной записи на задачу и `KEEP` у уникальной работы;
   повторная отправка после успеха невозможна — запись снимается сразу.
6. **Живой офлайн проверяется остановкой мока, а не `svc wifi disable`**: соединение идёт через
   `adb reverse`, и Wi-Fi на него не влияет.
