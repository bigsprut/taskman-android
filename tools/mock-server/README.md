# Мок-сервер состояния taskman

> **Это dev-инструмент для локальной разработки, а не сервер продукта.**
> Состояние он держит в памяти и умеет мутации задач, вложений и сотрудников,
> а теги, категории, списки, настройки и `/api/sync` здесь только читаются из
> фикстуры. Никогда не запускайте его как замену настоящему серверу и не
> переносите его значения по умолчанию (адрес, порт, токен) в продукт.

Мок нужен, чтобы запускать приложение и проверять руками контракт без доступа к
настоящему серверу владельца: авторизацию заголовком `Token`, привязку устройства
(TOFU), `ETag`/`304`, gzip, а с Ф1 — сценарии «создать / изменить / выполнить /
удалить задачу», с Ф3 — вложения и справочник сотрудников:

- `GET /api/state` — текущее состояние (soft-deleted задачи не попадают);
- `POST /api/tasks` — UPSERT списка задач полным объектом с CAS по `updated_at`;
- `POST /api/tasks/{id}/toggle` — смена статуса;
- `DELETE /api/tasks/{id}` — soft-delete задачи и всего поддерева;
- `POST /api/tasks/{id}/attachments` — multipart-аплоад вложения (поле `file`);
- `GET /api/attachments/{id}?token=…` — байты файла (токен ТОЛЬКО query);
- `DELETE /api/attachments/{id}` — soft-delete вложения;
- `POST/PATCH/DELETE /api/employees[/{id}]` — справочник сотрудников.

Фикстура читается **один раз при старте**, дальше сервер работает со своей копией
в памяти: после перезапуска все мутации пропадают, и снова отдаётся фикстура.
Загруженные файлы тоже живут только в памяти — на диск мок ничего не пишет.

Реализация — Python 3, **только стандартная библиотека**: `pip install` не нужен.
Multipart разбирается вручную по `boundary`: модуль `cgi` удалён в Python 3.13.

## Требования

- Python 3.7+ (проверено на 3.12.8 и 3.13.11 — в том числе разбор multipart без `cgi`).
- Больше ничего: скрипт использует только `http.server`, `gzip`, `hashlib`,
  `json`, `argparse` и прочую стандартную библиотеку.
- Windows, macOS, Linux.

## Запуск

Из корня репозитория (`taskman-android/`):

```powershell
# Windows PowerShell — порт по умолчанию 8443
python tools\mock-server\mock_state_server.py

# другой порт: если 8443 на машине уже занят
python tools\mock-server\mock_state_server.py --port 18443

# плюс снимок состояния в файл после каждой мутации
python tools\mock-server\mock_state_server.py --port 18443 --state-out state.local.json
```

```bash
# macOS / Linux
python3 tools/mock-server/mock_state_server.py --port 18443
```

Если `python` не найден, используйте лаунчер `py`:

```powershell
py tools\mock-server\mock_state_server.py --port 18443
```

Что нужно знать про адрес:

- по умолчанию сервер слушает `127.0.0.1:8443`, то есть доступен только с этой машины;
- в приложении (debug-сборка) base URL — `http://127.0.0.1:8443`;
- для Android-эмулятора `127.0.0.1` — это сам эмулятор, поэтому хост машины
  указывается как `http://10.0.2.2:8443`; если `10.0.2.2` не отвечает, рабочий
  путь — `adb reverse tcp:8443 tcp:8443` и адрес `http://127.0.0.1:8443`;
- для телефона в той же сети запускайте с `--host 0.0.0.0` и указывайте IP машины.
  Тогда стенд виден всей локальной сети — только на время проверки.

Остановка — **Ctrl+C**: сервер закрывает сокет и освобождает порт.

## Флаги

| Флаг | По умолчанию | Что делает |
|---|---|---|
| `--host` | `127.0.0.1` | адрес прослушивания; `0.0.0.0` — доступен из локальной сети |
| `--port` | `8443` | порт; `0` — взять любой свободный (сервер напечатает фактический) |
| `--token` | `local-dev-token` | ожидаемое значение заголовка `Token` |
| `--fixture` | `fixtures/api/state.sample.json` | путь к JSON-фикстуре; относительный путь считается от текущего каталога |
| `--device-gate` | `0` | первые N запросов с валидным токеном получают `403 device`; `0` — привязка выключена |
| `--state-out` | не задан | после каждой мутации сохранять текущее состояние в этот файл (смотреть глазами); по умолчанию не пишется |

Фикстура по умолчанию ищется относительно самого скрипта (`../../fixtures/api/state.sample.json`),
поэтому запускать сервер можно из любого каталога. После правки JSON перезапустите
сервер: на ходу фикстура не перечитывается, а изменения в памяти она бы затёрла.

## Что отдаёт

| Запрос | Ответ |
|---|---|
| `GET /api/state` с верным `Token` | `200`, текущее состояние, `ETag`, `Cache-Control: no-cache`, `Access-Control-Allow-Origin: *` |
| то же с `If-None-Match`, совпавшим с `ETag` | `304`, тела нет |
| `GET /api/state` без `Token` или с неверным | `401`, `{"error": "unauthorized"}` |
| `GET /api/state` при включённой привязке | `403`, `{"error": "device"}` — пока «ожидание» не закончится |
| `POST /api/tasks` | `200`, `{"saved": [{id, updated_at}], "server_time"}`; `409` при конфликте версий |
| `POST /api/tasks/{id}/toggle` | `200`, `{"id", "status", "updated_at"}`; `404` на неизвестный id |
| `DELETE /api/tasks/{id}` | `200`, `{"deleted": id}`; `404` на неизвестный id |
| `POST /api/tasks/{id}/attachments` | `200`, `{"id", "name", "size"}`; `400` — тело не multipart/нет поля `file`, `404` — неизвестная задача, `413` — файл больше 8 МиБ |
| `GET /api/attachments/{id}?token=…` | `200`, байты файла, `Content-Type: <mime>`, `Content-Disposition: inline; filename="…"`; `401` — нет/неверный токен; `404` — неизвестный или удалённый id |
| `DELETE /api/attachments/{id}` | `200`, `{"deleted": id}`; `404` — неизвестный или уже удалённый id |
| `POST /api/employees` | `200`, `{"id", "name"}`; `400` — пустое имя |
| `PATCH /api/employees/{id}` | `200`, обновлённая запись; `400` — пустое имя, `404` — неизвестный id |
| `DELETE /api/employees/{id}` | `200`, `{"deleted": id}`; `404` — неизвестный id |
| другой путь | `404`, `{"error": "not_found"}` |
| другой метод по известному пути | `405`, `{"error": "method_not_allowed"}` + заголовок `Allow` (сверх контракта, для наглядности) |
| `OPTIONS` | `204` с CORS-заголовками (сверх контракта, для браузерных проверок) |

Сверх контракта у мутаций бывают ещё коды: `400 {"error": "bad_request"}` — битое
тело/неизвестный статус/пустое имя, `413 {"error": "payload_too_large"}` — тело
больше 8 МиБ (у аплоада ограничение относится к самому файлу), `500
{"error": "fixture_not_object"}` — фикстура не JSON-объект (мутации некуда
применять, чтение при этом работает как раньше).

### Авторизация

Принимается **только** заголовок `Token`. Заголовок `X-Token` не читается — запрос
без `Token` получит `401`, как и на настоящем сервере. Имена заголовков в HTTP
регистронезависимы, поэтому `token: ...` тоже подойдёт; важно именно имя `Token`.
Значение токена в журнал не пишется — только `token=ok` / `token=invalid`.

**Исключение — `GET /api/attachments/{id}`** (ТЗ §4): этот путь принимает токен
**только** query-параметром `?token=…`, и заголовок `Token` он не читает. Запрос
с заголовком, но без `token=` получит `401`; так проверяется именно тот способ,
которым приложение (Coil/браузер) грузит картинки.

Сравнение не является защитой от подбора: это локальный стенд, а не сервер.

### Привязка устройства (TOFU)

`--device-gate N` включает эмуляцию: первые N запросов **с валидным токеном**
отвечают `403 {"error": "device"}`, дальше сервер работает нормально.
Запросы без токена счётчик не расходуют.

Счётчик общий на процесс (не по `X-Device-Id`): перезапуск сервера сбрасывает его.
Это позволяет проверить экран «устройство ждёт подтверждения» и автоповтор запроса.

```powershell
python tools\mock-server\mock_state_server.py --port 18444 --device-gate 1
```

## Мутации

Все мутации, кроме скачивания вложения, требуют тот же заголовок `Token`; тело —
JSON, кроме multipart-аплоада. Состояние меняется только в памяти и только после
успешного ответа: при `400`/`404`/`409`/`413` ничего не происходит. После любой
успешной мутации пересчитываются `server_time` и `ETag` (`GET /api/state` уже
отдаёт новое состояние).

### `POST /api/tasks` — UPSERT

Тело — **JSON-массив** `TaskIn` (даже для одной задачи):

```json
[
  {
    "id": null,
    "list_id": "9ace42b1640d4482a7c3ece0e49bc730",
    "parent_id": null,
    "category_id": null,
    "title": "Купить молоко",
    "content": "",
    "status": "created",
    "source": null,
    "tags": ["6658389f5881446a9fb65ae25ebdbe3e"],
    "created_at": null,
    "completed_at": null,
    "status_date": null,
    "updated_at": null
  }
]
```

Правила:

- `id: null` — новая задача: сервер генерирует id из 32 hex-символов. Если `id`
  передан, это UPSERT по нему; задачи с таким id в памяти нет — она создастся.
- **CAS:** если задача с таким id существует и `updated_at` передан и не совпал с
  текущим — `409 {"error": "conflict", "actual_updated_at": <текущий>}`. Сравнение
  с допуском меньше микросекунды: клиент может округлить `double` при
  сериализации, а «устаревшая» версия отличается на секунды. Конфликт в любом
  элементе массива отменяет **весь** запрос — «ничего не менять».
- `updated_at` в теле — только ожидаемая версия для CAS. В задачу всегда пишется
  текущее время сервера.
- **UPSERT полного объекта:** чего нет в теле, то получает дефолт, а не прежнее
  значение. Дефолты: `title`/`content` — `""`, `status` — `"created"`,
  `list_id` — первый список фикстуры, `parent_id`/`category_id`/`source` — `null`,
  `tags` — `[]`, `created_at` — `now`. Новая задача без `category_id` получает
  `settings.default_category` (ТЗ §4).
- `tags` — список id, **полностью заменяет** набор. Существование тегов мок не
  проверяет.
- `status`: `created | inprogress | completed | canceled`; легаси `needsAction`
  принимается как `created`; неизвестный статус — `400`.
- `status: "completed"` и `completed_at` не передан/`null` → `completed_at = now`;
  любой другой статус → `completed_at = null` (даже если прислали дату).
- `history`: запись `{"status", "ts"}` добавляется при смене статуса или явном
  `status_date` (тогда `ts` = `status_date`, иначе `now`). У новой задачи история
  пустая — как у задачи №51 в фикстуре; первая запись появится при первой смене.
- Серверные поля `deleted`, `attachments`, `history` в `TaskIn` не входят и
  сохраняются как были.

Ответ:

```json
{"saved": [{"id": "3f8be1189e1f4e4a88b133e79a9ee73a",
            "updated_at": 1795118210.2261724}],
 "server_time": 1795118210.226181}
```

### `POST /api/tasks/{id}/toggle` — смена статуса

```json
{"status": "completed"}
```

Меняет статус, `updated_at = now`, `completed_at` и `history` — по тем же правилам,
что и UPSERT (история пополняется только при реальной смене статуса). Неизвестный
id или soft-deleted задача → `404 {"error": "not_found"}`.

```json
{"id": "3f8be1189e1f4e4a88b133e79a9ee73a",
 "status": "completed",
 "updated_at": 1795118210.3186774}
```

### `DELETE /api/tasks/{id}` — soft-delete поддерева

У задачи и **всех** её потомков по `parent_id` (на любой глубине) выставляется
`deleted = 1` и `updated_at = now`, одним вызовом. Ответ: `{"deleted": "<id>"}`;
неизвестный id или повторное удаление — `404`.

Удалённые задачи (и их вложения) в `GET /api/state` не попадают. `toggle` и
`DELETE` по удалённой задаче отвечают `404`. UPSERT по id удалённой задачи
обновит запись, но `deleted` останется `1` — «воскрешения» в моке нет.

### `POST /api/tasks/{id}/attachments` — аплоад вложения

Тело — `multipart/form-data`, поле формы **обязательно** называется `file`
(ТЗ §4). Заголовок `Token` обязателен, как у остальных мутаций.

```powershell
curl.exe -i -F "file=@C:\temp\photo.png" -H "Token: local-dev-token" `
    http://127.0.0.1:8443/api/tasks/<task_id>/attachments
```

Ответ `200`:

```json
{"id": "6fcba704720e4c0f8d88e9a0e6381792", "name": "photo.png", "size": 12345}
```

- `id` — 32 hex; `name` — исходное имя файла; `size` — размер в байтах.
- В задаче появляется запись `attachments[]`:
  `{id, task_id, orig_name, mime, size, created_at, deleted: 0}`.
- `mime` берётся из `Content-Type` части, которую прислал клиент; если его нет —
  `application/octet-stream`.
- Файл **хранится в памяти** процесса и пропадает при перезапуске: временных
  файлов мок не пишет, чистить после остановки нечего. Вложения из фикстуры
  отдаются заглушкой объявленного размера — самих байт фикстура не содержит.
- Ограничение — **8 МиБ** на файл (тело запроса может быть чуть больше за счёт
  multipart-обвязки), больше — `413 {"error": "payload_too_large"}`. Если клиент
  шлёт `Expect: 100-continue` (curl на крупных файлах), мок отвечает `413` сразу,
  не дожидаясь тела.
- Неизвестный или удалённый `task_id` → `404`; тело не multipart или в нём нет
  поля `file` → `400 {"error": "bad_request"}`.
- `updated_at` задачи при аплоаде **не меняется**: CAS-версия после вложения
  остаётся прежней.

Разбор multipart — ручной, только стандартная библиотека: модуль `cgi` удалён в
Python 3.13. Boundary берётся из `Content-Type`, имя файла — из `filename="…"` в
`Content-Disposition`. Android (OkHttp) присылает имя в UTF-8, `curl.exe` на
Windows — в системной однобайтовой кодировке; мок пробует UTF-8, а затем cp1251.

### `GET /api/attachments/{id}?token=…` — скачивание

Токен — **только query-параметром**; заголовок `Token` этот путь не читает
(ТЗ §4). Успешный ответ — `200` с байтами файла, `Content-Type: <mime>` и
`Content-Disposition: inline; filename="…"`. Не-ASCII имя в заголовке
percent-кодируется: HTTP-заголовки не переносят кириллицу в latin-1.

```powershell
curl.exe -s -o photo.png "http://127.0.0.1:8443/api/attachments/<id>?token=local-dev-token"
```

- нет токена, неверный токен или заголовок вместо query → `401`;
- неизвестный id, вложение с `deleted = 1` или вложение удалённой задачи → `404`;
- gzip для файлов не применяется: байты отдаются как есть;
- `--device-gate` на скачивание не распространяется: картинки грузятся без
  `X-Device-Id`, счётчик «ожиданий» ими не расходуется.

### `DELETE /api/attachments/{id}` — soft-delete

Заголовок `Token`, как у остальных мутаций. Выставляет `deleted = 1`; ответ
`{"deleted": "<id>"}`. В `GET /api/state` вложение пропадает. Повторный
`DELETE` и `GET` удалённого вложения → `404`.

### Справочник сотрудников

Заголовок `Token`, тело — JSON `{"name": …}`. Имя обрезается по краям; пустое
(в том числе из одних пробелов) → `400 {"error": "bad_request"}`.

```powershell
# создать: 200 {"id": "<32 hex>", "name": "Иван"}
curl.exe -X POST -H "Token: local-dev-token" -H "Content-Type: application/json" `
    --data-binary "@emp.json" http://127.0.0.1:8443/api/employees
```

Здесь `emp.json` — файл в UTF-8 с телом `{"name": "Иван"}`: `curl.exe` на Windows
перекодирует аргументы командной строки в системную кодировку, а телу из файла
ничего не грозит. Для ASCII-имени можно и `--data-binary '{"name": "Ivan"}'`.

- `PATCH /api/employees/{id}` — переименование, ответ — обновлённая запись;
- `DELETE /api/employees/{id}` — запись **исчезает** из `state.employees`,
  ответ `{"deleted": "<id>"}`;
- неизвестный id → `404`. Отдельного `GET /api/employees` в моке нет: список
  приходит в `GET /api/state`.

## ETag и 304

`ETag` — это md5 канонического JSON **текущего** состояния **без поля
`server_time`** (`json.dumps(..., sort_keys=True, ensure_ascii=False,
separators=(",", ":")).encode("utf-8")`). `server_time` исключён намеренно:
сервер меняет его от запроса к запросу, иначе `304` не наступил бы никогда.

После каждой мутации `ETag` пересчитывается: повторный `GET` с прежним
`If-None-Match` обязан вернуть `200` с новым телом, а не `304`. `304` бывает
только на неизменённое состояние.

Ответ на совпавший `If-None-Match` — `304` без тела; поддерживаются несколько
значений через запятую, префикс `W/` и `*`. Тела у `304` нет, `Content-Length`
не отправляется.

## gzip

Если клиент прислал `Accept-Encoding: gzip` (или `x-gzip`, учитывается `q=0`),
тело сжимается и появляется `Content-Encoding: gzip`. `ETag` при этом не меняется:
он относится к несжатому представлению. Для `304` тела нет, поэтому
`Content-Encoding` тоже не отправляется. Сжатие работает и у ответов мутаций.

`curl` без `--compressed` покажет сжатые байты — это ожидаемо:
`curl.exe -s --compressed -H "Token: local-dev-token" http://127.0.0.1:8443/api/state`.

## `--state-out`

```powershell
python tools\mock-server\mock_state_server.py --port 18443 --state-out state.local.json
```

После каждой **успешной** мутации сервер пишет в файл то же состояние, что отдаёт
`GET /api/state`: без soft-deleted задач, с актуальным списком сотрудников, с
текущим `server_time`, в читаемом JSON (UTF-8, отступ 1 пробел). Файл пишется
атомарно (`*.tmp` + `os.replace`), так что никогда не содержит половину JSON.
Байты вложений в файл не попадают — только их описания из `attachments[]`.

- при старте файл не создаётся и не читается — только после первой мутации
  (в том числе аплоада вложения или изменения сотрудника);
- каталог должен существовать заранее, иначе сервер не стартует (код 2);
- ошибка записи (например, файл занят) печатается в stderr и не ломает ответ
  клиенту;
- файл удобно смотреть глазами и сравнивать с `GET /api/state`. В git его
  коммитить не нужно (имя `state.local.json` — просто пример).

## Журнал

Одна строка на запрос, в stdout:

```
[2026-11-19 22:57:12] POST /api/tasks -> 409 (304: no) token=ok device_id=present device_wait_left=0 gzip=yes bytes_sent=81 result=conflict
```

Поля: время, метод, путь, код, признак `304`, состояние токена (`ok`/`invalid`/`n/a`),
наличие `X-Device-Id` (`present`/`absent`, без значения), сколько «ожиданий»
устройства осталось, сжималось ли тело, сколько байт отправлено и результат:

| `result=` | Когда |
|---|---|
| `state` / `not_modified` | `GET /api/state`: обычный ответ или `304` |
| `upsert created=N updated=M` | успешный `POST /api/tasks` |
| `conflict` | CAS-конфликт (`409`) |
| `toggled status=…` | успешный `toggle` |
| `deleted tasks=N` | soft-delete, N — сколько задач попало в поддерево |
| `attachment_upload name=… size=…` | успешный аплоад вложения |
| `attachment_get` | успешное скачивание вложения |
| `attachment_deleted` | soft-delete вложения |
| `attachment_missing` | `404` при скачивании (неизвестный/удалённый id) |
| `attachment_too_large` | `413` — файл больше 8 МиБ |
| `employee_created` / `employee_updated` / `employee_deleted` | справочник сотрудников |
| `not_found`, `bad_request`, `unauthorized`, `method_not_allowed` … | отказы |
| `preflight` | `OPTIONS` |

Токен в журнал не попадает. В пути запроса значение query-параметра `token`
маскируется, поэтому строка скачивания выглядит как
`GET /api/attachments/<id>?token=***`. При перенаправлении вывода в файл журнал
пишется в UTF-8. Служебные сообщения (ошибки разбора запроса, проблемы
`--state-out`) идут в stderr.

## Ручные проверки

PowerShell 7+ (`-SkipHttpErrorCheck` нужен, чтобы видеть тела ошибок):

```powershell
$base = 'http://127.0.0.1:8443'

# 1) без токена -> 401 {"error": "unauthorized"}
(Invoke-WebRequest "$base/api/state" -SkipHttpErrorCheck).Content

# 2) с токеном -> 200 и 51 задача
$state = Invoke-RestMethod "$base/api/state" -Headers @{ Token = 'local-dev-token' }
$state.tasks.Count        # 51

# 3) ETag и повторный запрос -> 304 без тела
$r = Invoke-WebRequest "$base/api/state" -Headers @{ Token = 'local-dev-token' } -SkipHttpErrorCheck
$etag = @($r.Headers['ETag'])[0]
$r304 = Invoke-WebRequest "$base/api/state" -SkipHttpErrorCheck `
    -Headers @{ Token = 'local-dev-token'; 'If-None-Match' = $etag }
[int]$r304.StatusCode     # 304
$r304.Content.Length      # 0

# 4) привязка устройства: сервер запущен с --device-gate 1
[int](Invoke-WebRequest "$base/api/state" -Headers @{ Token = 'local-dev-token' } -SkipHttpErrorCheck).StatusCode  # 403
[int](Invoke-WebRequest "$base/api/state" -Headers @{ Token = 'local-dev-token' } -SkipHttpErrorCheck).StatusCode  # 200

# 5) неизвестный путь -> 404 {"error": "not_found"}
(Invoke-WebRequest "$base/api/nope" -SkipHttpErrorCheck).Content
```

### Сценарий `__ТЕСТ`: создать, проверить, устаревшая правка, верная правка

Четыре запроса, которые проверяют весь контракт мутаций (сервер — на порту 18555,
запущен с `--token local-dev-token`):

```powershell
$base = 'http://127.0.0.1:18555'
$h = @{ Token = 'local-dev-token' }
$listId = '9ace42b1640d4482a7c3ece0e49bc730'   # единственный список фикстуры

# подготовка: ETag до сценария — для шага 2
$etagBefore = @((Invoke-WebRequest "$base/api/state" -Headers $h).Headers['ETag'])[0]

# 1) создать: id = null -> 200, saved[0].id — новый id из 32 hex
$task = [ordered]@{
    id = $null; list_id = $listId; parent_id = $null; category_id = $null
    title = '__ТЕСТ через мок'; content = 'из README'; status = 'created'
    source = $null; tags = @()
    created_at = $null; completed_at = $null; status_date = $null; updated_at = $null
}
$created = Invoke-RestMethod "$base/api/tasks" -Method Post -Headers $h `
    -ContentType 'application/json; charset=utf-8' `
    -Body (ConvertTo-Json -InputObject @($task) -Depth 5)
$id = $created.saved[0].id
$created.server_time -gt 0              # True

# 2) задача в состоянии, ETag сменился
$state = Invoke-WebRequest "$base/api/state" -Headers $h
$taskNow = ($state.Content | ConvertFrom-Json).tasks | Where-Object id -eq $id
$taskNow.title                          # __ТЕСТ через мок
$etagNow = @($state.Headers['ETag'])[0]
$etagNow -ne $etagBefore                # True
$rOld = Invoke-WebRequest "$base/api/state" -SkipHttpErrorCheck -Headers @{
    Token = 'local-dev-token'; 'If-None-Match' = $etagBefore }
[int]$rOld.StatusCode                   # 200, а не 304: состояние изменилось

# 3) правка с УСТАРЕВШИМ updated_at -> 409, состояние не меняется
$stale = [ordered]@{
    id = $id; list_id = $listId; parent_id = $null; category_id = $null
    title = '__ТЕСТ: устаревшая правка'; content = ''; status = 'created'
    source = $null; tags = @()
    created_at = $null; completed_at = $null; status_date = $null; updated_at = 1.0
}
$r409 = Invoke-WebRequest "$base/api/tasks" -Method Post -Headers $h `
    -ContentType 'application/json; charset=utf-8' `
    -Body (ConvertTo-Json -InputObject @($stale) -Depth 5) -SkipHttpErrorCheck
[int]$r409.StatusCode                   # 409
$r409.Content                           # {"error": "conflict", "actual_updated_at": ...}

# 4) правка с АКТУАЛЬНЫМ updated_at -> 200
$ok = [ordered]@{
    id = $id; list_id = $listId; parent_id = $null; category_id = $null
    title = '__ТЕСТ через мок (обновлена)'; content = ''; status = 'created'
    source = $null; tags = @()
    created_at = $null; completed_at = $null; status_date = $null
    updated_at = $taskNow.updated_at
}
$r200 = Invoke-WebRequest "$base/api/tasks" -Method Post -Headers $h `
    -ContentType 'application/json; charset=utf-8' `
    -Body (ConvertTo-Json -InputObject @($ok) -Depth 5) -SkipHttpErrorCheck
[int]$r200.StatusCode                   # 200

# продолжение: выполнено, потом soft-delete
Invoke-RestMethod "$base/api/tasks/$id/toggle" -Method Post -Headers $h `
    -ContentType 'application/json' -Body '{"status": "completed"}'
Invoke-RestMethod "$base/api/tasks/$id" -Method Delete -Headers $h
```

`curl` (есть в Windows 10+):

```powershell
curl.exe -i http://127.0.0.1:8443/api/state                                   # 401
curl.exe -i -H "Token: local-dev-token" http://127.0.0.1:8443/api/state       # 200 + ETag
curl.exe -i -H "Token: local-dev-token" -H "If-None-Match: \"<ETag>\"" http://127.0.0.1:8443/api/state  # 304
curl.exe -s --compressed -H "Token: local-dev-token" http://127.0.0.1:8443/api/state  # gzip
curl.exe -i -X DELETE -H "Token: local-dev-token" http://127.0.0.1:8443/api/tasks/<id>  # {"deleted": "<id>"}
```

### Сценарий Ф3: вложение и сотрудники

Мок на порту 18600 с `--token local-dev-token`; `<task_id>` — id из `/api/state`.

```powershell
$base = 'http://127.0.0.1:18600'
$taskId = '340c5a71af6f48d389b6e8bd2ecaa8ff'

# 1) аплоад: поле формы обязательно file -> 200 {"id","name","size"}
'hello attachments' | Set-Content "$env:TEMP\mock-attach.txt" -NoNewline
$up = curl.exe -s -F "file=@$env:TEMP\mock-attach.txt" `
    -H "Token: local-dev-token" "$base/api/tasks/$taskId/attachments" | ConvertFrom-Json
$up.id; $up.name; $up.size              # <32 hex>; mock-attach.txt; 17

# 2) вложение в состоянии, ETag сменился
$state = Invoke-RestMethod "$base/api/state" -Headers @{ Token = 'local-dev-token' }
($state.tasks | Where-Object id -eq $taskId).attachments | Where-Object id -eq $up.id

# 3) скачивание: только ?token= -> 200 и те же байты
curl.exe -s -o "$env:TEMP\mock-downloaded.txt" "$base/api/attachments/$($up.id)?token=local-dev-token"
(Get-FileHash "$env:TEMP\mock-downloaded.txt").Hash -eq (Get-FileHash "$env:TEMP\mock-attach.txt").Hash  # True
curl.exe -s -o NUL -w "%{http_code}`n" "$base/api/attachments/$($up.id)"                               # 401
curl.exe -s -o NUL -w "%{http_code}`n" -H "Token: local-dev-token" "$base/api/attachments/$($up.id)"   # 401

# 4) удаление: 200, повторный GET -> 404
curl.exe -s -X DELETE -H "Token: local-dev-token" "$base/api/attachments/$($up.id)"
curl.exe -s -o NUL -w "%{http_code}`n" "$base/api/attachments/$($up.id)?token=local-dev-token"          # 404

# 5) сотрудники: создать, переименовать, удалить
'{"name": "  Иван  "}' | Set-Content "$env:TEMP\emp.json" -Encoding utf8
$emp = curl.exe -s --data-binary "@$env:TEMP\emp.json" -H "Content-Type: application/json" `
    -H "Token: local-dev-token" "$base/api/employees" | ConvertFrom-Json
$emp.name                                  # Иван (имя обрезано по краям)
'{"name": "Иван Петров"}' | Set-Content "$env:TEMP\emp2.json" -Encoding utf8
curl.exe -s -X PATCH --data-binary "@$env:TEMP\emp2.json" -H "Content-Type: application/json" `
    -H "Token: local-dev-token" "$base/api/employees/$($emp.id)"            # обновлённая запись
curl.exe -s -X DELETE -H "Token: local-dev-token" "$base/api/employees/$($emp.id)"  # {"deleted": "<id>"}
```

Ожидаемые коды: аплоад и скачивание — `200`; без токена и с заголовком вместо
query — `401`; после удаления — `404`; пустое имя сотрудника — `400`; неизвестный
id сотрудника — `404`; файл больше 8 МиБ — `413`.

## Что можно проверить руками в приложении

- вход с токеном `local-dev-token`; неверный токен → экран входа (401);
- при `--device-gate 1` первый запрос уходит в «ожидание подтверждения» (403),
  приложение пережидает и повторяет — второй запрос даёт 200;
- список задач: 51 задача из фикстуры, статусы, категории;
- повторный опрос: приложение получает `304` и берёт тело из кэша OkHttp
  (в журнале `304: yes`);
- gzip: ответ приходит с `Content-Encoding: gzip`, тело разжимается;
- **создание задачи**: после `POST /api/tasks` она видна в списке и остаётся там
  после повторного `/api/state` (в журнале `result=upsert created=1 updated=0`);
- **правка/выполнение**: `toggle` в `completed` заполняет `completed_at` и
  добавляет историю; возврат в `created` её очищает;
- **удаление**: задача и подзадачи исчезают из `/api/state`; повторное удаление в
  приложении даёт 404 — это нормально для мока;
- конфликт версий: можно смоделировать руками — обновить задачу в приложении,
  затем отправить `POST /api/tasks` с прежним `updated_at` (например, из PowerShell
  по сценарию выше) и увидеть `409` и реакцию клиента;
- **аплоад вложения**: выбрать файл/картинку в задаче (или «Поделиться в Задачи»)
  → после `POST /api/tasks/{id}/attachments` запись есть в `/api/state`, в журнале
  `result=attachment_upload name=… size=…`;
- **превью картинки**: картинка грузится по `GET /api/attachments/{id}?token=…`
  (в журнале `?token=***` и `result=attachment_get`); без токена — `401`;
- **удаление вложения**: вложение исчезает из задачи и из `/api/state`, повторное
  удаление и скачивание дают `404`;
- **сотрудники**: создание/переименование/удаление в приложении отражается в
  `state.employees`; пустое имя → `400`, неизвестный id → `404`;
- недоступный сервер: остановите мок и посмотрите, как приложение показывает ошибку;
- `--state-out` — открыть файл после мутаций и сравнить с `GET /api/state`.

## Ограничения

- **Состояние только в памяти**: перезапуск возвращает фикстуру, история мутаций
  не сохраняется.
- Мутации: `POST /api/tasks` (UPSERT + CAS), `POST /api/tasks/{id}/toggle`,
  `DELETE /api/tasks/{id}`, `POST /api/tasks/{id}/attachments`,
  `GET`/`DELETE /api/attachments/{id}`, `POST/PATCH/DELETE /api/employees[/{id}]`.
  `/api/tags`, `/api/categories`, `/api/lists`, `/api/settings` и `/api/sync`
  по-прежнему только читаются из фикстуры.
- **Байты вложений живут в памяти процесса**: после перезапуска они пропадают,
  на диск мок ничего не пишет и при остановке чистить нечего. Вложения из
  фикстуры отдаются заглушкой объявленного размера — настоящих байт в фикстуре нет.
- Ссылочная целостность не проверяется: id списка, категории и тегов мок
  принимает как есть, даже если такого объекта в фикстуре нет.
- «Воскрешения» soft-deleted задачи нет: UPSERT сохраняет `deleted = 1`.
- Файл `--state-out` — снимок, а не вход: сервер его не читает и не восстанавливает
  из него состояние. Байты вложений в него не попадают.
- `--device-gate` — глобальный счётчик запросов, а не проверка `X-Device-Id`;
  скачивание вложений по `?token=` его не расходует.
- Тело запроса читается по `Content-Length`; chunked-тела не поддерживаются
  (Retrofit/OkHttp и curl по умолчанию шлют длину). Потолок JSON-тела — 8 МиБ;
  у аплоада 8 МиБ относится к файлу, плюс до 64 КиБ на multipart-обвязку.
- Префикс пути в base URL (`http://host:port/prefix`) не поддерживается: запрос
  `/prefix/api/state` получит `404`.
- `405`, `OPTIONS`, `400`, `413`, `500 fixture_not_object` — ответы сверх
  описанного контракта, добавлены для удобства и диагностики.
- Мок не заменяет интеграционные тесты и не проверяет серверную логику: он лишь
  воспроизводит формат ответа и коды. В частности, `updated_at` задачи при
  аплоаде вложения не меняется, а у сотрудников нет версий/истории.
- Android: cleartext `http://` в release-сборке запрещён; debug-сборка такой адрес
  разрешает. Если запрос не уходит, ищите причину в настройках сети приложения
  (`app/**`), а не в моке.

## Безопасность

- По умолчанию сервер слушает только `127.0.0.1` — снаружи он недоступен.
- Токен по умолчанию — учебный, для локального стенда. Не используйте здесь
  настоящие токены и не коммитьте их.
- `--host 0.0.0.0` открывает стенд всей локальной сети: только на время проверки
  и только в доверенной сети.
- В журнал не попадает значение токена: печатается только `token=ok/invalid`, а
  query-параметр `token` в пути маскируется как `?token=***`.
- `GET /api/attachments/{id}?token=…` по контракту несёт токен в URL: он может
  осесть в истории браузера, прокси или логах клиента. Для dev-стенда это
  допустимо, но настоящий токен так гонять не стоит.
- `--state-out` содержит задачи из фикстуры и ваши тестовые правки: не кладите
  туда секреты и не коммитьте файл.
