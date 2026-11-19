# TASKS Ф3 — карточки для исполнителей

Правила передачи и окружение — из `TASKS.md` (Ф0), `TASKS-F1.md`, `TASKS-F2.md`.
Сборку и прогон тестов ведёт один процесс (ведущий).

## Статус карточек (итог Ф3)

| Карточка | Исполнитель | Статус | Итог |
|---|---|---|---|
| F3-01 Мок: вложения и сотрудники | `mock-f3` | ✅ | multipart `file`, `GET ?token=`, DELETE, CRUD сотрудников; 79 живых проверок + регрессия Ф1 |
| F3-02 Тесты вложений и сотрудников | `tests-f3` | ✅ | 40 тестов: URL, имена файлов, upload/delete, downloader, сотрудники, формат размера |
| F3-03 Данные и сеть | ведущий | ✅ | `Attachment`, `AttachmentStore`, `AttachmentUrls`, `LocalFileSource`, `AttachmentDownloader`, `EmployeeRepository` |
| F3-04 UI редактора (вложения) | ведущий | ✅ | превью Coil, открыть/поделиться/удалить, photo picker + выбор файла |
| F3-05 UI настроек (сотрудники) | ведущий | ✅ | добавить/переименовать/удалить с подтверждением |
| F3-06 «Поделиться в Задачи» | ведущий | ✅ | intent-filter, `ShareInbox`, диалог выбора задачи, загрузка |
| F3-07 Отчёт и PR | ведущий | ✅ | `PHASE-REPORT.md`, `QUESTIONS.md` Q14–Q17 |

## Контракты Ф3 (для продолжения работы)

- `object AttachmentUrls { fun build(baseUrl, token, attachmentId): String? }` — единственное
  место, где токен попадает в URL (у `/api/attachments/{id}` он только в query, §4).
- `interface AttachmentStore { observeAttachments(taskId); findAttachment(id) }` —
  реализовано в `DatabaseStateStore`.
- `class AttachmentRepository(api, store, files: LocalFileSource, tasks, connection, downloader)`:
  `upload(taskId, uri)`, `delete(id)`, `download(attachment)`, `url(id)`, `observeAttachments(taskId)`.
- `class AttachmentDownloader(client: OkHttpClient, cacheDirectory: File)` — пишет в
  `cacheDir/attachments`, имя санитизируется (`safeAttachmentFileName`).
- `class EmployeeRepository(api, tasks)`: `create/rename/delete` → `MutationOutcome`.
- `ShareInbox` (в `AppContainer`) — очередь uri из `ACTION_SEND`.

## Правила, добытые в Ф3

1. **Поле multipart обязано называться `file`** (§4) — иначе сервер не примет.
2. **Токен к файлу — только query-параметр**; для таких запросов нужен клиент **без**
   `ConnectionInterceptor`, иначе он подменит хост/путь и добавит бесполезный заголовок.
3. **Наружу файл отдаётся только через FileProvider** с `FLAG_GRANT_READ_URI_PERMISSION`;
   `AttachmentIntents.launch` возвращает `false`, если просмотрщика нет — UI показывает сообщение.
4. **Coil 3 настраивается один раз** в `TaskmanApp` через `SingletonImageLoader.Factory`
   и использует файловый клиент (общий кэш).
5. При расширении `TaskmanApi` **обязательно обновить оба набора фейков** — в
   `testutil/TestDoubles.kt` и в `androidTest/.../EditorTestDoubles.kt` (иначе
   `connectedDebugAndroidTest` не компилируется).
6. Живой тест `ACTION_SEND` из `adb shell` не даёт приложению право чтения
   `content://media/...` — проверять шаринг надо через реального отправителя или
   `am start --grant-read-uri-permission` с последующей проверкой (в нашем прогоне файл
   не прочитался; сам аплоад проверен через выбор файла).
