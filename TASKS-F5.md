# TASKS Ф5 — карточки для исполнителей

Правила передачи и окружение — из `TASKS.md` (Ф0) и `TASKS-F1.md`…`TASKS-F4.md`.
Сборку и прогон тестов ведёт один процесс (ведущий).

## Статус карточек (итог Ф5)

| Карточка | Исполнитель | Статус | Итог |
|---|---|---|---|
| F5-01 Мок: теги/категории/настройки | `mock-f5` | ✅ | CRUD справочников, `PATCH /api/settings`, чистка ссылок при удалении; 151+38 живых проверок |
| F5-02 Тесты справочников/сортировки/экспорта | `tests-f5` | ✅ | 53 теста: цвета, сортировка, markdown на фикстуре (40 задач, 16 `[x]`), каталоги, VM |
| F5-03 Q18 «Отправить сейчас» | ведущий | ✅ | прямой `flush()` из баннера (WorkManager-`KEEP` подвёл на живой проверке — исправлено) |
| F5-04 API и репозиторий справочников | ведущий | ✅ | `CatalogRepository`, DTO, явный `null` в `PATCH /api/settings` |
| F5-05 Сортировка и категория по умолчанию | ведущий | ✅ | `TaskSortOrder` + DataStore, `observeDefaultCategoryId`, чипы в настройках |
| F5-06 Экспорт markdown | ведущий | ✅ | `MarkdownExport` по снапшоту Room + «Поделиться» |
| F5-07 Стриминг-аплоад (Q16) | ведущий | ✅ | `LocalFileHandle` + `StreamRequestBody`, chunked при неизвестном размере |
| F5-08 Отчёт и PR | ведущий | ✅ | `PHASE-REPORT.md`, `QUESTIONS.md` Q20–Q21 |

## Контракты Ф5 (для продолжения работы)

- `CatalogRepository(api, tasks)` — `createTag/updateTag/deleteTag`, `createCategory/…`,
  `setDefaultCategory(id?)`, всё → `MutationOutcome` + рефетч.
- `MarkdownExport.build(snapshot: ExportSnapshot, generatedAtLabel, statusLabel, formatDate)` —
  чистая функция; формат зафиксирован тестом на фикстуре.
- `TaskSortOrder` (`STATUS`/`FRESHNESS`/`TITLE`) — `comparator()`, `fromKey`.
- `CatalogColor.normalize` — единственная точка нормализации цветов.
- `LocalFileSource.open(uri): LocalFileHandle?` — потоковый аплоад.
- `TaskMutationRepository.syncNow()` — ручная отправка очереди (кнопка Q18).

## Правила, добытые в Ф5

1. **`PATCH /api/settings` с `JsonObject`**, а не DTO: иначе явный `null` теряется и «снять
   категорию» становится «ничего не делать».
2. **Кнопка «Отправить сейчас» не должна ждать WorkManager**: `ExistingWorkPolicy.KEEP` может
   держать предыдущую работу в backoff — отправляем `flush()` напрямую, планировщик — страховка.
3. **Порядок сортировки — локальная настройка**; подзадачи сортируются `STATUS_ORDER` всегда.
4. **Экспорт — чистая функция**: строки/даты инжектируются, поэтому тест на фикстуре не тянет
   Android; пустые категории не выводятся (Q21).
5. **Цвета нормализуются до отправки**; невалидный ввод остаётся в диалоге с подсказкой.
