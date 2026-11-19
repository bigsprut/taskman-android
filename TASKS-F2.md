# TASKS Ф2 — карточки для исполнителей

Правила передачи и окружение — из `TASKS.md` (Ф0) и `TASKS-F1.md` (Ф1): карточка = цель +
файлы-владельцы + входные контракты + DoD-команда + запреты; два исполнителя не правят один
файл. Сборку и прогон тестов ведёт один процесс (ведущий).

## Статус карточек (итог Ф2)

| Карточка | Исполнитель | Статус | Итог |
|---|---|---|---|
| F2-01 Markdown: парсер и рендер | `md-render` | ✅ | `ui/markdown/**`: модель, парсер на `org.jetbrains:markdown`, `MarkdownText` (Compose M3), 21 тест парсера |
| F2-02 Тесты редактора | `tests-f2` | ✅ | 26 тестов: payload/черновик/VM (включая регресс §9-Ф2)/контракт сохранения |
| F2-03 UI-регресс на устройстве | ведущий | ✅ | instrumented `EditorDraftRegressionTest` — 1 тест, зелёный на эмуляторе API 37 |
| F2-04 Домен и данные | ведущий | ✅ | `TaskDraft`, `Employee`, `TaskHistoryEntry`; `StateStore`+DAO; `TaskPayload.fromDraft/newSubtask`; `saveDraft/addSubtask/renameSubtask` |
| F2-05 ViewModel редактора | ведущий | ✅ | черновик в `SavedStateHandle`, dirty, автосохранение при закрытии, подзадачи без потери черновика |
| F2-06 UI листа | ведущий | ✅ | все поля, markdown-превью, даты (`DatePickerDialog`), история, подзадачи |
| F2-07 Отчёт и PR | ведущий | ✅ | `PHASE-REPORT.md`, `QUESTIONS.md` (Q10–Q13) |

## Ключевые контракты Ф2 (для продолжения работы)

- `domain/TaskDraft` — `@Serializable` черновик: `id, listId, parentId, title, content, status,
  categoryId, tagIds, source, createdAt, completedAt, statusDateTs, updatedAt`.
- `ui/editor/TaskEditorViewModel`: `open(taskId)`, `close()` (сохраняет при dirty),
  `setTitle/setContent/setStatus/setStatusDate/setCategory/toggleTag/setEmployee/setCreatedAt`,
  `addSubtask/renameSubtask/toggleSubtask/deleteSubtask`, `state: StateFlow<EditorUiState>`,
  `messages: SharedFlow<EditorMessage>`.
- `data/repo/TaskMutationRepository`: `saveDraft(draft)`, `addSubtask(parentId, title)`,
  `renameSubtask(taskId, title)` — все возвращают `MutationOutcome`, после успеха `refresh()`.
- `ui/markdown/MarkdownText(markdown, modifier)` — рендер; `MarkdownParser.parse(markdown)`.

## Правила, добытые в Ф2 (не нарушать)

1. Внутри `LazyColumn` нельзя вкладывать `verticalScroll` — падает с
   `IllegalStateException: infinity maximum height constraints`. Превью markdown рендерится
   без собственного скролла.
2. `Espresso 3.7` не работает на API 37 (удалён `InputManager.getInstance`); для
   instrumented-тестов подключён `3.8.0-alpha04` через version catalog.
3. Черновик редактора никогда не пересобирается из снапшота, пока лист открыт: только из
   `SavedStateHandle` (при восстановлении) или при `open()`.
