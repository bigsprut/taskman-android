# TASKS — нарезка Ф0 на подзадачи для передачи исполнителям

Документ описывает, как работа фазы Ф0 разбита на небольшие подзадачи. Каждая карточка
самодостаточна: исполнителю не нужен полный контекст проекта, только поля карточки.

Ссылки на требования — на `TZ-01-android-app.md` (далее «ТЗ»). План фазы — `PLAN.md`.

## Статус карточек (итог Ф0)

| Карточка | Статус | Итог |
|---|---|---|
| T-01 Сборка зелёная | ✅ | `clean test assembleDebug lintDebug` — BUILD SUCCESSFUL, lint: no issues |
| T-02 Юнит-тесты домена и репозитория | ✅ | 57 тестов (9 файлов), включая 2 добавленных ведущим на «неполный снапшот» и «сбой хранилища» |
| T-03 Контрактные сетевые тесты | ✅ | 7 тестов на настоящем HTTP (заголовки, base URL, gzip, ETag/304, 401/403) |
| T-04 Мок-сервер на фикстуре | ✅ | `tools/mock-server/` (Python 3, stdlib), проверен вручную и использован в живом прогоне |
| T-05 Аудит соответствия ТЗ | ✅ | найдено 4 дефекта + замечания; все дефекты исправлены, замечания учтены |
| T-06 Отчёт фазы и README | ✅ | `PHASE-REPORT.md`, раздел «Сборка» в `README.md` |
| T-07 Коммит и push ветки | ✅ | ветка `phase/0-skeleton` запушена |

Найденные аудитом дефекты, исправленные в этой же ветке: 401 с экрана ожидания устройства не
вёл на вход; необработанные сбои DataStore/Keystore; гонка «выход vs. идущая синхронизация»;
автоповтор 30 с продолжал работать в фоне. Дополнительно: токен больше не попадает в
`rememberSaveable`, `ConnectionHolder` стал атомарным, ответ `200 {}` больше не стирает кэш,
ошибка локального хранилища отличается от «неизвестного формата ответа».

## 0. Правила передачи

**В карточку входит ровно это:**

1. цель и требования (номер раздела ТЗ, а не пересказ всего ТЗ);
2. файлы, которые исполнитель создаёт/правит (**и только они**);
3. входные контракты — сигнатуры типов/функций, которые уже есть в коде;
4. критерий готовности и команда проверки;
5. запреты (что нельзя трогать).

**В карточку НЕ входит:** история обсуждений, полный текст ТЗ, код, который исполнитель не
меняет, чужие файлы. Если для работы не хватает контекста — вопрос ведущему, а не чтение
всего репозитория.

**Правила владения файлами.** Две подзадачи никогда не правят один файл одновременно.
Матрица владения — в §5. Ведущий (Алина) владеет `app/src/main/**`, сборкой и прогоном
тестов; исполнители — своими подкаталогами.

**Окружение (общее для всех):**

- ОС Windows, репозиторий: `D:\work\Learn\DSHarness\TaskmanNative\taskman-android`.
- JDK 17 обязателен (системная Java 11 не годится):
  `$env:JAVA_HOME='C:\Program Files\Zulu\zulu-17'`.
- Android SDK: `D:\Android\SDK` (прописан в `local.properties`, файл gitignored).
- Сборка: `.\gradlew.bat :app:assembleDebug`, тесты: `.\gradlew.bat :app:testDebugUnitTest`.
- Gradle **9.6.0**, AGP **9.5.1** (built-in Kotlin), Kotlin **2.3.21**, KSP **2.3.14**,
  Compose BOM **2026.09.00**, Room **2.8.5**, Retrofit **3.0.0**, OkHttp **5.5.1**,
  kotlinx.serialization **1.13.0**.

**Жёсткие запреты (ТЗ §7):** не менять `fixtures/**`, `TZ-01-android-app.md`, серверную
часть; не добавлять аналитику/краш-репортеры/платные зависимости; токены, адреса инстансов
и `X-Device-Id` не попадают в код, коммиты и логи; `local.properties` не коммитить.

## 1. Состояние на момент нарезки

Каркас Ф0 написан ведущим: сборка (Gradle-скрипты, каталог версий), манифест и ресурсы,
тема M3, сетевой слой, Room-кэш, репозиторий и сортировка, экраны входа / ожидания
устройства / списка / настроек. Осталось: подтвердить сборку, покрыть тестами, дать
инструмент локального стенда, провести аудит соответствия ТЗ и оформить отчёт фазы.

## 2. Карточки подзадач

### T-01 — Сборка зелёная (владелец: ведущий)

- **Цель:** `:app:assembleDebug` собирается на JDK 17 из чистого клона; версии AGP/Kotlin/
  Compose зафиксированы и обоснованы в отчёте (ТЗ §5.1).
- **Файлы:** `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`,
  `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`,
  `app/src/main/**`.
- **Готово, когда:** `assembleDebug` зелёный, APK в `app/build/outputs/apk/debug/`.
- **Запреты:** отключать lint/тесты ради зелени, поднимать `compileSdk` выше 37 (ТЗ §5.1).

### T-02 — Юнит-тесты домена, парсера и репозитория (исполнитель: субагент)

- **Цель:** покрыть JVM-тестами разбор контракта §6, маппинг, сортировку `STATUS_ORDER` и
  обработку ответов сервера в репозитории.
- **Файлы (владелец):** `app/src/test/java/io/github/bigsprut/taskman/domain/**`,
  `.../data/repo/**`, `.../data/remote/UrlNormalizerTest.kt`,
  `.../data/remote/ApiFailureTest.kt`, `.../ui/common/ColorParsingTest.kt`,
  `.../testutil/**` (общие тестовые дублёры).
- **Входные контракты:** сигнатуры в §4 (ниже). Фикстура доступна как ресурс тестов
  `/api/state.sample.json` (в `app/build.gradle.kts` каталог `fixtures/` подключён к
  `test`-ресурсам).
- **Обязательные случаи:** разбор фикстуры (51 задача, 2 категории, 4 сотрудника, 3 тега,
  1 список, 11 подзадач, 4 вложения, статусы 29/3/19); `needsAction` → `created`; `deleted`
  0/1 → boolean; `statusDateTs` из истории и фолбэки; сборка `task_tags`/`attachments`/
  `history`; порядок `inprogress → created → completed → canceled` и «свежесть даты
  статуса» в группе; детерминированный tie-break; исходы `refresh()`: success / 401 / 403
  device / IOException / неразбираемое тело; `hasAttempted`/`lastFailure`/`lastSuccessAtMillis`;
  `UrlNormalizer` (нормализация, https-only в release, http в debug, префикс пути);
  `parseTaskmanColor` (#RRGGBB, #AARRGGBB, мусор → fallback).
- **Готово, когда:** ведущий прогоняет `:app:testDebugUnitTest` и все тесты зелёные;
  число тестов зафиксировано в отчёте фазы.
- **Запреты:** менять `app/src/main/**`, `fixtures/**`; добавлять зависимости; запускать
  Gradle (сборку ведёт один процесс — конфликты блокировок).

### T-03 — Контрактные сетевые тесты на реальном HTTP (исполнитель: субагент)

- **Цель:** проверить поведение клиента по фактам §4: заголовки `Token`/`X-Device-Id`,
  подстановка base URL, 401/403, gzip и `ETag`/304 через OkHttp `Cache`.
- **Файлы (владелец):** `app/src/test/java/io/github/bigsprut/taskman/net/**`.
- **Инструменты:** `com.sun.net.httpserver.HttpServer` из JDK (свой мини-сервер в тесте),
  временный каталог для кэша, `NetworkFactory(cacheDirectory, connection)`, подставной
  `StateStore`. Новых зависимостей не добавлять.
- **Обязательные случаи:** оба заголовка доходят до сервера (`Token` именно с этим
  именем); при base URL `http://127.0.0.1:<port>/prefix` запрос идёт на этот хост/порт и с
  префиксом пути; `403 {"error":"device"}` → `SyncOutcome.DeviceNotApproved`; `401` →
  `SyncOutcome.Unauthorized`; gzip-тело разжимается и парсится; ответ 200 с `ETag` +
  `Cache-Control: no-cache` → повторный запрос несёт `If-None-Match`, сервер отвечает 304,
  клиент получает то же тело из кэша.
- **Готово, когда:** ведущий прогоняет `:app:testDebugUnitTest` — зелёно.
- **Запреты:** те же, что T-02; никаких обращений в интернет.

### T-04 — Мок-сервер на фикстуре для локальной разработки (исполнитель: субагент)

- **Цель:** дать стенд для ручной проверки и эмулятора (ТЗ §8: «разработка против
  локального мока на фикстуре»), не трогая сервер владельца.
- **Файлы (владелец):** `tools/mock-server/**`.
- **Требования:** Python 3, только стандартная библиотека; `GET /api/state` отдаёт
  `fixtures/api/state.sample.json`; gzip; `ETag` (md5 тела без `server_time`) и 304 на
  `If-None-Match`; проверка заголовка `Token` (`--token`, по умолчанию фиксированный
  тестовый) → 401; необязательный режим `--device-gate` (первые N запросов →
  `403 {"error":"device"}`); флаги `--host`, `--port`, `--device-gate N`;
  короткий README с примерами запуска; самопроверка в конце работы (запуск + запрос
  через PowerShell/curl).
- **Готово, когда:** сервер запускается, отдаёт 200/304/401/403 по описанным условиям,
  README содержит команду запуска.
- **Запреты:** менять `fixtures/**`, ходить в интернет, коммитить токены.

### T-05 — Аудит соответствия ТЗ (исполнитель: субагент, только чтение)

- **Цель:** независимо проверить, что код Ф0 соответствует ТЗ §5.1–5.4, §6, §7, §9-Ф0, и
  вернуть список расхождений с `файл:строка` и ссылкой на пункт ТЗ.
- **Файлы:** ничего не менять. Результат — отчёт ведущему (не коммитится отдельным файлом).
- **Что проверять:** M3-каркас, insets/edge-to-edge, стабильные ключи LazyColumn, adaptive
  icon/monochrome, splash, predictive back, тач-таргеты ≥48dp, `contentDescription`,
  фон строк в ресурсах, отсутствие секретов в коде/логах, `allowBackup=false`, обработка
  401/403/409/прочих 4xx, сортировка, раздельность состояний экрана, отсутствие запрещённых
  зависимостей.
- **Готово, когда:** список находок отсортирован по важности (блокер / дефект / замечание),
  каждая — с обоснованием по ТЗ.

### T-06 — Отчёт фазы и инструкция сборки (владелец: ведущий)

- **Цель:** `PHASE-REPORT.md` (что сделано, версии, результат `test`/`assembleDebug`, число
  тестов из `app/build/test-results`, раздел «server», ограничения, вопросы) и раздел
  сборки в `README.md` (JDK 17, `local.properties`, команды).
- **Готово, когда:** отчёт соответствует §8 ТЗ и проверяем: команды из него выполняются.

### T-07 — Коммит и push ветки (владелец: ведущий)

- **Цель:** ветка `phase/0-skeleton` с кодом, тестами, `PLAN.md`, `TASKS.md`,
  `PHASE-REPORT.md`; push и PR (main защищён).
- **Готово, когда:** `git push` прошёл, ссылка на PR в отчёте.

## 3. Нарезка следующих фаз (для планирования, не в этой ветке)

| Фаза | Подзадачи (по одной карточке на строку) |
|---|---|
| Ф1 | API мутаций (`POST /api/tasks` UPSERT + `updated_at` CAS, `toggle`, `DELETE`); маппинг «полный объект из Room»; фильтры/счётчики в UI; подзадачи (сворачивание, ▸ N/M); чекбокс статуса; `SwipeToDismissBox` + диалог; бейджи; тесты CAS-конфликта 409 |
| Ф2 | Модель листа-редактора + автосохранение при любом закрытии; поля/даты/история; чипы тегов и категорий; инлайн-подзадачи; markdown-рендер (обосновать библиотеку); регрессионный UI-тест «несохранённые поля + действие с вложением» |
| Ф3 | Multipart-аплоад (`file`) и photo picker; `ACTION_SEND`-приём; превью Coil с `?token=`; FileProvider и внешний просмотрщик; share/удаление; CRUD сотрудников |
| Ф4 | Room как единственный источник правды; outbox-очередь (WorkManager, backoff); «только IOException в очередь», 4xx — тост; 409 → рефетч + явный конфликт; тесты самолётного режима |
| Ф5 | CRUD тегов/категорий (цвет), категория по умолчанию, порядок сортировки, экспорт markdown; тесты на `/api/state` |

## 4. Входные контракты (то, на что опираются тесты)

```kotlin
// domain
enum class TaskStatus(val wireValue: String, val sortIndex: Int) {
    IN_PROGRESS, CREATED, COMPLETED, CANCELED;
    companion object { fun fromWireValue(raw: String?): TaskStatus }
}
data class Task(
    val id: String, val title: String, val status: TaskStatus, val statusDateTs: Double,
    val parentId: String?, val categoryTitle: String?, val categoryColor: String?, val source: String?,
) { val isSubtask: Boolean }
object StatusOrder { val comparator: Comparator<Task>; fun sort(tasks: List<Task>): List<Task> }

// remote
val TaskmanJson: Json                       // ignoreUnknownKeys, explicitNulls=false, isLenient=false
interface TaskmanApi { suspend fun getState(): Response<StateResponse> }   // @GET("api/state")
interface ConnectionProvider { fun baseUrl(): String?; fun token(): String?; fun deviceId(): String? }
class ConnectionHolder : ConnectionProvider { fun set(baseUrl: String?, token: String?, deviceId: String?); fun clearToken() }
class NetworkFactory(cacheDirectory: File, connection: ConnectionProvider) {
    val api: TaskmanApi
    fun okHttpClient(): OkHttpClient
    companion object { const val PLACEHOLDER_BASE_URL: String }   // "http://127.0.0.1/"
}
object UrlNormalizer {
    fun normalize(raw: String): String
    fun isValid(raw: String, allowCleartext: Boolean): Boolean
    fun endpointFor(baseUrl: String, requestUrl: HttpUrl): HttpUrl?
}
sealed class ApiFailure : Exception {
    class Network(cause: Throwable); object Unauthorized; object DeviceNotApproved
    class Conflict(val actualUpdatedAt: Double?); class Http(val code: Int, val serverError: String?)
    class Malformed(cause: Throwable)
    companion object { fun fromHttpResponse(code: Int, errorBody: ResponseBody?): ApiFailure }
}

// repo
data class StateSnapshot(lists, categories, employees, tags, tasks, attachments, taskTags, history, defaultCategoryId)
interface StateStore {
    suspend fun replaceSnapshot(snapshot: StateSnapshot)
    fun observeTasks(): Flow<List<Task>>
    suspend fun clear()
}
object StateMapper { fun toSnapshot(response: StateResponse): StateSnapshot; fun statusDateTs(task: TaskDto): Double }
sealed interface SyncOutcome { data class Success(val taskCount: Int); object Unauthorized; object DeviceNotApproved; data class Failed(val failure: ApiFailure) }
data class SyncStatus(val isRefreshing: Boolean, val hasAttempted: Boolean, val lastFailure: ApiFailure?, val lastSuccessAtMillis: Long?)
class TaskRepository(api: TaskmanApi, stateStore: StateStore, now: () -> Long = System::currentTimeMillis) {
    val syncStatus: StateFlow<SyncStatus>
    fun observeTasks(): Flow<List<Task>>
    suspend fun refresh(): SyncOutcome
    suspend fun clearLocalData()
}

// ui
fun parseTaskmanColor(raw: String?, fallback: Color): Color
```

Факты фикстуры (эталон тестов, проверены): задач 51 (`inprogress` 3, `created` 29,
`completed` 19), категорий 2, тегов 3, сотрудников 4, списков 1, подзадач 11, вложений 4,
`settings.default_category = "ee374424b6d340a8b6fece6c6dee36a3"`.

## 5. Матрица владения файлами

| Путь | Владелец | Кто ещё читает |
|---|---|---|
| `app/src/main/**`, `app/build.gradle.kts`, `gradle/**`, `*.gradle.kts`, `gradle.properties` | ведущий | все |
| `app/src/test/java/.../domain/**`, `.../data/repo/**`, `.../data/remote/UrlNormalizerTest.kt`, `.../data/remote/ApiFailureTest.kt`, `.../ui/common/ColorParsingTest.kt`, `.../testutil/**` | T-02 | — |
| `app/src/test/java/.../net/**` | T-03 | — |
| `tools/mock-server/**` | T-04 | — |
| `PLAN.md`, `TASKS.md`, `PHASE-REPORT.md`, `README.md` | ведущий | T-05 (чтение) |
| `fixtures/**`, `TZ-01-android-app.md` | владелец продукта | все (чтение) |

## 6. Команды проверки

```powershell
# сборка
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-17'
cd D:\work\Learn\DSHarness\TaskmanNative\taskman-android
.\gradlew.bat :app:assembleDebug --no-daemon

# юнит-тесты и число тестов
.\gradlew.bat :app:testDebugUnitTest --no-daemon
Get-ChildItem app\build\test-results\testDebugUnitTest\*.xml |
  ForEach-Object { ([xml](Get-Content $_)).testsuite.tests } | Measure-Object -Sum

# локальный стенд (T-04)
python tools\mock-server\mock_state_server.py --port 8443 --token local-dev-token
```
