#!/usr/bin/env python3
"""
Мок-сервер состояния taskman для локальной разработки.

ЭТО НЕ СЕРВЕР ПРОДУКТА И НЕ ЭМУЛЯТОР БЭКЕНДА ЦЕЛИКОМ. Состояние читается из
фикстуры `fixtures/api/state.sample.json` в память при старте и дальше меняется
только мутациями клиента; после перезапуска всё возвращается к фикстуре.
Поддерживается минимальный набор записи, который нужен клиенту Ф1–Ф3:

  * `GET /api/state` — текущее состояние (soft-deleted задачи не попадают);
  * `POST /api/tasks` — UPSERT списка задач полным объектом с CAS по `updated_at`;
  * `POST /api/tasks/{id}/toggle` — смена статуса задачи;
  * `DELETE /api/tasks/{id}` — soft-delete задачи и всего поддерева;
  * `POST /api/tasks/{id}/attachments` — multipart-аплоад (поле формы `file`);
  * `GET /api/attachments/{id}?token=…` — байты файла (токен ТОЛЬКО query);
  * `DELETE /api/attachments/{id}` — soft-delete вложения;
  * `POST/PATCH/DELETE /api/employees[/{id}]` — справочник сотрудников.

Теги, категории, списки, настройки и `/api/sync` только читаются из фикстуры и
не меняются. Значения по умолчанию (адрес 127.0.0.1, порт 8443, токен) — только
для локального стенда, в продукт их переносить нельзя.

Реализация — только стандартная библиотека Python 3, pip-зависимостей нет.

Что проверяется этим сервером (контракт из ТЗ §4, §6):
  * авторизация заголовком `Token` (именно `Token`; `X-Token` не принимается),
    иначе 401 {"error": "unauthorized"};
  * эмуляция привязки устройства (TOFU): первые N запросов с валидным токеном
    отвечают 403 {"error": "device"}, далее сервер работает нормально
    (флаг --device-gate N, по умолчанию выключено);
  * `ETag: "<md5>"` — md5 канонического JSON без поля `server_time`; после
    мутации ETag меняется, и старый `If-None-Match` больше не даёт 304;
  * `If-None-Match` совпал с ETag — 304 без тела;
  * `Accept-Encoding: gzip` — сжатое тело и `Content-Encoding: gzip`;
  * CAS: расхождение `updated_at` → 409 {"error": "conflict",
    "actual_updated_at": ...}, состояние при этом не меняется;
  * `status=completed` без `completed_at` → `completed_at` = now; возврат в
    другой статус очищает `completed_at`; история статусов пополняется при
    смене статуса или явном `status_date`;
  * флаг `--state-out PATH` — после каждой мутации кладёт текущее состояние
    в файл (смотреть глазами), по умолчанию не пишется;
  * multipart разбирается вручную по boundary: модуль `cgi` в Python 3.13
    удалён, сторонние библиотеки не используются. Файл хранится в памяти;
    потолок файла — 8 МиБ, больше — 413. Поле формы обязано называться `file`;
  * `GET /api/attachments/{id}` принимает токен только query-параметром
    `?token=…` (ТЗ §4); заголовок `Token` на этом пути не читается;
  * вложения из фикстуры отдаются заглушкой объявленного размера: самих байт
    фикстура не содержит;
  * остальные пути — 404 {"error": "not_found"}.

Запуск (из корня репозитория):
    python tools/mock-server/mock_state_server.py
    python tools/mock-server/mock_state_server.py --port 18443 --device-gate 1
    python tools/mock-server/mock_state_server.py --port 18443 --state-out state.local.json

Остановка — Ctrl+C.
"""

import argparse
import copy
import gzip
import hashlib
import json
import locale
import os
import re
import signal
import sys
import threading
import time
import uuid
from collections import namedtuple
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, unquote

# --- значения по умолчанию -------------------------------------------------

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8443
DEFAULT_TOKEN = "local-dev-token"

#: Поддерживаемые пути API.
STATE_PATH = "/api/state"
TASKS_PATH = "/api/tasks"
EMPLOYEES_PATH = "/api/employees"
#: `/api/tasks/{id}/toggle`; id декодируется из percent-encoding отдельно.
TOGGLE_PATH_RE = re.compile(r"^/api/tasks/(?P<task_id>[^/]+)/toggle$")
#: `/api/tasks/{id}/attachments` — multipart-аплоад (поле формы `file`).
ATTACH_UPLOAD_PATH_RE = re.compile(r"^/api/tasks/(?P<task_id>[^/]+)/attachments$")
#: `/api/tasks/{id}`.
TASK_PATH_RE = re.compile(r"^/api/tasks/(?P<task_id>[^/]+)$")
#: `/api/attachments/{id}`: GET — байты по `?token=…`, DELETE — soft-delete.
ATTACHMENT_PATH_RE = re.compile(r"^/api/attachments/(?P<attachment_id>[^/]+)$")
#: `/api/employees/{id}` — PATCH/DELETE карточки сотрудника.
EMPLOYEE_PATH_RE = re.compile(r"^/api/employees/(?P<employee_id>[^/]+)$")

#: Маршрут -> разрешённые методы. OPTIONS разрешён на всех маршрутах.
ROUTE_METHODS = {
    "state": ("GET", "HEAD"),
    "tasks": ("POST",),
    "toggle": ("POST",),
    "task": ("DELETE",),
    "attachment_upload": ("POST",),
    "attachment": ("GET", "DELETE"),
    "employees": ("POST",),
    "employee": ("PATCH", "DELETE"),
}

#: Статусы задачи из контракта (ТЗ §4); `needsAction` — легаси-синоним `created`.
TASK_STATUSES = ("created", "inprogress", "completed", "canceled")
LEGACY_STATUSES = {"needsAction": "created"}

#: Потолок тела JSON-запроса: защищает стенд от случайной мегабайтной отправки.
MAX_BODY_BYTES = 8 * 1024 * 1024

#: Потолок одного вложения (файла), как и у JSON-тела, — 8 МиБ (ТЗ §4).
MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024

#: Запас на multipart-обвязку (заголовки частей и boundary): тело запроса может
#: быть чуть больше 8 МиБ, а ограничение относится к самому файлу.
MULTIPART_OVERHEAD_BYTES = 64 * 1024

#: Допуск при сравнении `updated_at`: клиент может округлить double при
#: сериализации, а «устаревшая» версия отличается на секунды.
VERSION_EPSILON = 1e-6

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DEFAULT_FIXTURE = os.path.join(REPO_ROOT, "fixtures", "api", "state.sample.json")

HEADER_TOKEN = "Token"
HEADER_IF_NONE_MATCH = "If-None-Match"
HEADER_ACCEPT_ENCODING = "Accept-Encoding"
HEADER_DEVICE_ID = "X-Device-Id"

#: Заголовки, которые сервер отдаёт вместе с телом (кроме ETag у ошибок).
CORS_HEADER = ("Access-Control-Allow-Origin", "*")

# --- ошибки контракта ------------------------------------------------------


class BadRequestError(Exception):
    """Тело запроса не соответствует контракту (ответ 400)."""


class PayloadTooLargeError(Exception):
    """Тело больше MAX_BODY_BYTES (ответ 413)."""


class ConflictError(Exception):
    """CAS-конфликт версий задачи (ответ 409); хранит actual_updated_at."""

    def __init__(self, actual_updated_at):
        super().__init__("conflict")
        self.actual_updated_at = actual_updated_at


class NotFoundError(Exception):
    """Задачи с таким id нет (или она soft-deleted) — ответ 404."""


class FixtureNotObjectError(Exception):
    """Фикстура — не JSON-объект: состояние в памяти недоступно, мутации запрещены."""


# --- фикстура и ETag -------------------------------------------------------


def compute_etag(document):
    """
    Считает ETag тела ответа: md5 канонического JSON без поля `server_time`.

    `server_time` исключается намеренно: сервер меняет его от запроса к
    запросу, и если бы он входил в ETag, ответ 304 не случился бы никогда.

    Сериализация: `sort_keys=True`, UTF-8 без экранирования кириллицы, чтобы
    значение не зависело от порядка ключей и от настроек локали.
    """
    if isinstance(document, dict):
        payload = {key: value for key, value in document.items() if key != "server_time"}
    else:
        payload = document
    canonical = json.dumps(
        payload,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.md5(canonical.encode("utf-8")).hexdigest()


def encode_state(document):
    """
    Тело ответа: JSON в том же читаемом виде, что и фикстура (отступ 1 пробел).

    Кириллица не экранируется: так ответ удобно смотреть в curl и в файле
    `--state-out`.
    """
    return json.dumps(document, ensure_ascii=False, indent=1).encode("utf-8")


class StateFixture:
    """Загруженная фикстура: байты файла, разобранный JSON, ETag и статистика."""

    def __init__(self, path):
        self.path = os.path.abspath(path)
        with open(self.path, "rb") as handle:
            self.raw = handle.read()
        # utf-8-sig — на случай BOM в начале файла; отдаём при этом ровно те
        # байты, которые лежат на диске.
        self.doc = json.loads(self.raw.decode("utf-8-sig"))
        self.etag = compute_etag(self.doc)
        self.tasks = len(self.doc.get("tasks") or []) if isinstance(self.doc, dict) else 0


# --- проверка типов тела ---------------------------------------------------


def _text(value, field):
    """Строка; отсутствующее значение (None) даёт пустую строку."""
    if value is None:
        return ""
    if not isinstance(value, str):
        raise BadRequestError("поле {0} должно быть строкой".format(field))
    return value


def _optional_text(value, field):
    """Строка или None."""
    if value is None:
        return None
    if not isinstance(value, str):
        raise BadRequestError("поле {0} должно быть строкой или null".format(field))
    return value


def _optional_number(value, field):
    """Число (unix-секунды) или None; bool числом не считается."""
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BadRequestError("поле {0} должно быть числом или null".format(field))
    return float(value)


def _tag_ids(value):
    """`tags` — список id; None считается пустым списком."""
    if value is None:
        return []
    if not isinstance(value, list):
        raise BadRequestError("поле tags должно быть массивом id")
    result = []
    for entry in value:
        if not isinstance(entry, str) or not entry:
            raise BadRequestError("tags: элементы должны быть непустыми строками")
        result.append(entry)
    return result


def normalize_status(value):
    """Приводит статус к контрактному виду; неизвестный статус — 400."""
    if value is None:
        return "created"
    if not isinstance(value, str):
        raise BadRequestError("status должен быть строкой")
    status = value.strip()
    if not status:
        raise BadRequestError("status не может быть пустым")
    status = LEGACY_STATUSES.get(status, status)
    if status not in TASK_STATUSES:
        raise BadRequestError("неизвестный статус: {0}".format(status))
    return status


def parse_json_body(body):
    """Разбирает JSON-тело; пустое или битое тело — 400."""
    if not body:
        raise BadRequestError("пустое тело запроса")
    try:
        return json.loads(body.decode("utf-8-sig"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise BadRequestError("невалидный JSON: {0}".format(exc))


# --- multipart/form-data без cgi -------------------------------------------
# Модуль `cgi` удалён в Python 3.13, а тащить сторонние библиотеки на стенд
# не хочется. Разбор ручной по boundary: curl и OkHttp шлют именно такой
# multipart, а `email.parser` не используем намеренно — он разбирает тело
# построчно и может исказить двоичные байты вложений.


#: Поле формы `file`: (имя файла, mime, байты).
MultipartFile = namedtuple("MultipartFile", "orig_name mime data")


def _split_outside_quotes(value, separator):
    """Делит строку по separator, не заглядывая внутрь кавычек."""
    parts = []
    current = []
    quoted = False
    escaped = False
    for char in value:
        if escaped:
            current.append(char)
            escaped = False
        elif char == "\\" and quoted:
            current.append(char)
            escaped = True
        elif char == '"':
            quoted = not quoted
            current.append(char)
        elif char == separator and not quoted:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return parts


def _unquote(value):
    """Снимает экранирование внутри кавычек: `\\"` -> `"`, `\\\\` -> `\\`."""
    result = []
    escaped = False
    for char in value:
        if escaped:
            result.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        else:
            result.append(char)
    if escaped:
        result.append("\\")
    return "".join(result)


def parse_header_params(value):
    """
    Разбирает `Content-Type`/`Content-Disposition`: (`основной тип`, {параметры}).

    Кавычки у значений снимаются, `\\"`/`\\\\` разэкранируются. Имена файлов с
    кавычками присылают и curl (`\\"`), и OkHttp (percent-кодирует в `%22`).
    """
    parts = _split_outside_quotes(value or "", ";")
    main = parts[0].strip().lower()
    params = {}
    for chunk in parts[1:]:
        name, separator, raw = chunk.partition("=")
        if not separator:
            continue
        parsed = raw.strip()
        if len(parsed) >= 2 and parsed[0] == '"' and parsed[-1] == '"':
            parsed = _unquote(parsed[1:-1])
        params[name.strip().lower()] = parsed
    return main, params


def _decode_header_text(raw):
    """
    Декодирует байты заголовка части multipart.

    Android (OkHttp) присылает имя файла в UTF-8, а `curl.exe` на Windows —
    в системной однобайтовой кодировке (на русской машине cp1251). Сначала
    пробуем строгий UTF-8, потом локальную кодировку и cp1251; совсем битые
    байты заменяем, чтобы запрос не падал из-за имени файла.
    """
    candidates = ["utf-8"]
    preferred = locale.getpreferredencoding(False)
    if preferred and preferred.lower().replace("-", "") != "utf8":
        candidates.append(preferred)
    candidates.extend(["cp1251", "latin-1"])
    for encoding in candidates:
        try:
            return raw.decode(encoding)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", "replace")


def parse_part_headers(raw):
    """
    Заголовки одной части multipart: имена в нижнем регистре, перенос строк склеен.

    Части приходят с `\\r\\n`, но принимаем и `\\n`: так удобнее тестировать
    мок вручную, а curl/OkHttp всё равно шлют CRLF.
    """
    headers = {}
    last = None
    for line in raw.replace(b"\r\n", b"\n").split(b"\n"):
        if not line.strip():
            continue
        if line[:1] in (b" ", b"\t") and last is not None:
            headers[last] += " " + _decode_header_text(line.strip())
            continue
        name, separator, value = line.partition(b":")
        if not separator:
            continue
        key = name.strip().decode("ascii", "replace").lower()
        headers[key] = _decode_header_text(value.strip())
        last = key
    return headers


def _clean_header(value):
    """Значение для HTTP-заголовка: печатный ASCII без управляющих символов."""
    text = (value or "").strip()
    if not text:
        return ""
    if any(ord(char) < 0x20 or ord(char) > 0x7E for char in text):
        return ""
    return text


def parse_multipart_file(content_type, body):
    """
    Достаёт поле формы `file` из multipart/form-data.

    Требования контракта: тело обязано быть multipart, а поле формы — `file`.
    Иначе BadRequestError (400). Части с другими именами игнорируются.
    """
    media_type, params = parse_header_params(content_type)
    if media_type != "multipart/form-data":
        raise BadRequestError("ожидался multipart/form-data")
    boundary = (params.get("boundary") or "").strip()
    if not boundary:
        raise BadRequestError("в Content-Type нет boundary")
    if not body:
        raise BadRequestError("пустое тело запроса")
    delimiter = b"--" + boundary.encode("utf-8", "replace")
    chunks = body.split(delimiter)
    if len(chunks) < 2:
        raise BadRequestError("тело не похоже на multipart: boundary не найден")

    # chunks[0] — preamble до первого разделителя; дальше части, последняя
    # начинается с `--` (закрывающий разделитель `--boundary--`).
    for chunk in chunks[1:]:
        if chunk.startswith(b"--"):
            break
        if chunk.startswith(b"\r\n"):
            chunk = chunk[2:]
        elif chunk.startswith(b"\n"):
            chunk = chunk[1:]
        head, separator, data = chunk.partition(b"\r\n\r\n")
        if not separator:
            head, separator, data = chunk.partition(b"\n\n")
        if not separator:
            continue
        headers = parse_part_headers(head)
        disposition, disposition_params = parse_header_params(
            headers.get("content-disposition", "")
        )
        if not disposition.startswith("form-data"):
            continue
        if disposition_params.get("name") != "file":
            continue
        # MIME кладёт CRLF между телом части и boundary — он не часть файла.
        if data.endswith(b"\r\n"):
            data = data[:-2]
        elif data.endswith(b"\n"):
            data = data[:-1]
        orig_name = disposition_params.get("filename") or "file"
        mime = _clean_header(headers.get("content-type")) or "application/octet-stream"
        return MultipartFile(orig_name, mime, bytes(data))
    raise BadRequestError("в multipart нет поля file")


def content_disposition_inline(orig_name):
    """
    `inline; filename="…"` для ответа вложения.

    Значение заголовка http.server кодирует в latin-1, поэтому всё не-ASCII
    percent-кодируем заранее — иначе `send_header` упадёт на кириллице, а в
    ответе будет мусор.
    """
    return 'inline; filename="{0}"'.format(quote(orig_name or "file", safe=""))


def log_text(value):
    """Текст для строки журнала: журнал — одна строка на запрос, без переводов строк."""
    return re.sub(r"[\r\n\t]", " ", str(value or ""))


def _fixture_attachment_bytes(attachment):
    """
    Заглушка для вложения из фикстуры: самих байт в ней нет, только описание.

    Размер берём из `size`, чтобы `Content-Length` совпадал с состоянием; для
    `text/*` заполняем текстом, остальное — нулями. Это не файл продукта, а
    только проверка пути скачивания.
    """
    try:
        size = max(0, int(attachment.get("size") or 0))
    except (TypeError, ValueError):
        size = 0
    size = min(size, MAX_ATTACHMENT_BYTES)
    mime = (attachment.get("mime") or "").lower()
    if mime.startswith("text/"):
        sample = "Файл из фикстуры: байт в ней нет, это заглушка мока.\n".encode("utf-8")
        return (sample * (size // len(sample) + 1))[:size]
    return b"\x00" * size


def _same_version(expected, actual):
    """Сравнивает версии задач с допуском на округление double."""
    if actual is None:
        return expected is None
    try:
        return abs(float(expected) - float(actual)) < VERSION_EPSILON
    except (TypeError, ValueError):
        return False


def _employee_name(payload):
    """
    Имя сотрудника из тела `{name}`.

    Имя обрезается по краям (как и на сервере), пустое — 400: запись без имени
    в справочнике бессмысленна.
    """
    if not isinstance(payload, dict):
        raise BadRequestError("тело должно быть JSON-объектом с полем name")
    name = _text(payload.get("name"), "name").strip()
    if not name:
        raise BadRequestError("name не может быть пустым")
    return name


# --- состояние в памяти ----------------------------------------------------


class StateStore:
    """
    Состояние в памяти, загруженное из фикстуры один раз при старте.

    Дальше фикстура не читается: всё, что клиент изменил мутациями, живёт до
    перезапуска сервера. RLock сериализует снапшот и мутации — сервер
    многопоточный, а один запрос может править поддерево задач.
    """

    def __init__(self, fixture):
        self._fixture = fixture
        self._lock = threading.RLock()
        doc = fixture.doc
        # Фикстура-не-объект: прежнее поведение — отдаём файл как есть,
        # мутации недоступны (иначе некуда класть задачи).
        self._static = not isinstance(doc, dict)
        self._base = {}
        self._tasks = []
        self._index = {}
        self._employees = []
        self._employee_index = {}
        #: id вложения -> (задача, запись вложения в задаче).
        self._attachments = {}
        #: id вложения -> байты файла (только для загруженных на этот стенд).
        self._blobs = {}
        if self._static:
            return
        self._base = {
            key: copy.deepcopy(value)
            for key, value in doc.items()
            if key not in ("tasks", "server_time")
        }
        # deepcopy: фикстура остаётся только для чтения, а мутации меняют задачи на месте.
        self._tasks = copy.deepcopy(doc.get("tasks") or [])
        for task in self._tasks:
            task.setdefault("deleted", 0)
            task.setdefault("attachments", [])
            task.setdefault("tags", [])
            task.setdefault("history", [])
        self._index = {task["id"]: task for task in self._tasks if task.get("id")}
        self._employees = copy.deepcopy(doc.get("employees") or [])
        self._employee_index = {
            employee["id"]: employee
            for employee in self._employees
            if isinstance(employee, dict) and employee.get("id")
        }
        for task in self._tasks:
            for attachment in task.get("attachments") or []:
                if isinstance(attachment, dict) and attachment.get("id"):
                    self._attachments[attachment["id"]] = (task, attachment)

    # --- чтение ---------------------------------------------------------

    @property
    def fixture(self):
        return self._fixture

    def total_tasks(self):
        """Сколько задач лежит в памяти, включая soft-deleted."""
        with self._lock:
            return len(self._tasks)

    def current(self):
        """
        Текущее состояние для `GET /api/state`: (документ, тело, ETag).

        Soft-deleted задачи и их вложения в ответ не попадают. `server_time`
        ставится текущий и в ETag не входит (см. compute_etag).
        """
        with self._lock:
            if self._static:
                return None, self._fixture.raw, self._fixture.etag
            document = self._snapshot_locked()
            return document, encode_state(document), compute_etag(document)

    def _snapshot_locked(self):
        """Копия состояния без удалённых задач; порядок ключей — как в фикстуре."""
        tasks = []
        for task in self._tasks:
            if task.get("deleted"):
                continue
            item = dict(task)
            item["attachments"] = [
                dict(attachment)
                for attachment in task.get("attachments") or []
                if not attachment.get("deleted")
            ]
            item["tags"] = list(task.get("tags") or [])
            item["history"] = [dict(entry) for entry in task.get("history") or []]
            tasks.append(item)

        now = time.time()
        document = {}
        for key, value in self._fixture.doc.items():
            if key == "tasks":
                document["tasks"] = tasks
            elif key == "employees":
                # Справочник сотрудников живёт в памяти: удалённые исчезают, новые появляются.
                document["employees"] = [dict(employee) for employee in self._employees]
            elif key == "server_time":
                document["server_time"] = now
            else:
                document[key] = value
        document.setdefault("tasks", tasks)
        document.setdefault("employees", [dict(employee) for employee in self._employees])
        document.setdefault("server_time", now)
        return document

    # --- мутации --------------------------------------------------------

    def upsert(self, items):
        """
        UPSERT списка задач (`POST /api/tasks`).

        Сначала проверяются все элементы, и только потом применяются: при любом
        CAS-конфликте или ошибке тела состояние не меняется вообще.
        Возвращает (ответ клиенту, сводка для журнала).
        """
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        if not isinstance(items, list):
            raise BadRequestError("тело должно быть JSON-массивом TaskIn")
        with self._lock:
            plans = [self._plan_task(item) for item in items]
            now = time.time()
            saved = []
            created = 0
            for plan in plans:
                task, was_created = self._apply_plan(plan, now)
                if was_created:
                    created += 1
                saved.append({"id": task["id"], "updated_at": task["updated_at"]})
            response = {"saved": saved, "server_time": time.time()}
        summary = "created={0} updated={1}".format(created, len(saved) - created)
        return response, summary

    def _plan_task(self, item):
        """
        Проверяет один TaskIn и собирает всё нужное для применения.

        Ничего не меняет: если в списке окажется конфликт, «ничего не менять»
        должно относиться ко всему запросу.
        """
        if not isinstance(item, dict):
            raise BadRequestError("элемент массива должен быть объектом TaskIn")

        raw_id = item.get("id")
        existing = None
        if raw_id is not None:
            if not isinstance(raw_id, str) or not raw_id.strip():
                raise BadRequestError("id должен быть непустой строкой или null")
            existing = self._index.get(raw_id.strip())

        status = normalize_status(item.get("status"))
        expected = _optional_number(item.get("updated_at"), "updated_at")
        if existing is not None and expected is not None:
            if not _same_version(expected, existing.get("updated_at")):
                raise ConflictError(existing.get("updated_at"))

        category_id = _optional_text(item.get("category_id"), "category_id")
        if existing is None and category_id is None:
            # ТЗ §4: новая задача без категории получает категорию по умолчанию.
            category_id = self._default_category_id()

        return {
            "id": existing["id"] if existing is not None else uuid.uuid4().hex,
            "existing": existing,
            "list_id": _optional_text(item.get("list_id"), "list_id") or self._first_list_id(),
            "parent_id": _optional_text(item.get("parent_id"), "parent_id"),
            "category_id": category_id,
            "title": _text(item.get("title"), "title"),
            "content": _text(item.get("content"), "content"),
            "status": status,
            "source": _optional_text(item.get("source"), "source"),
            "tags": _tag_ids(item.get("tags")),
            "created_at": _optional_number(item.get("created_at"), "created_at"),
            "completed_at": _optional_number(item.get("completed_at"), "completed_at"),
            "status_date": _optional_number(item.get("status_date"), "status_date"),
        }

    def _apply_plan(self, plan, now):
        """Создаёт или перезаписывает задачу по плану. Возвращает (задача, создана)."""
        existing = plan["existing"]
        status = plan["status"]
        was_created = existing is None
        status_changed = False

        if was_created:
            task = {
                "id": plan["id"],
                "list_id": plan["list_id"],
                "parent_id": plan["parent_id"],
                "category_id": plan["category_id"],
                "title": plan["title"],
                "content": plan["content"],
                "status": status,
                "created_at": plan["created_at"] if plan["created_at"] is not None else now,
                "updated_at": now,
                "completed_at": None,
                "deleted": 0,
                "source": plan["source"],
                "attachments": [],
                "tags": list(plan["tags"]),
                "history": [],
            }
            self._tasks.append(task)
            self._index[task["id"]] = task
        else:
            task = existing
            status_changed = task.get("status") != status
            # UPSERT полного объекта: что не передано — дефолт, а не прежнее значение.
            task["list_id"] = plan["list_id"]
            task["parent_id"] = plan["parent_id"]
            task["category_id"] = plan["category_id"]
            task["title"] = plan["title"]
            task["content"] = plan["content"]
            task["status"] = status
            task["source"] = plan["source"]
            task["tags"] = list(plan["tags"])
            if plan["created_at"] is not None:
                task["created_at"] = plan["created_at"]
            task["updated_at"] = now

        # completed_at пересчитывается по статусу, а не по тому, что прислали.
        if status == "completed":
            if plan["completed_at"] is not None:
                task["completed_at"] = plan["completed_at"]
            elif task.get("completed_at") is None:
                task["completed_at"] = now
        else:
            task["completed_at"] = None

        # История пишется при смене статуса или явном status_date (ТЗ §4);
        # новой задачи это не касается: у неё не было прежнего статуса.
        if (not was_created and status_changed) or plan["status_date"] is not None:
            ts = plan["status_date"] if plan["status_date"] is not None else now
            task["history"].append({"status": status, "ts": ts})

        return task, was_created

    def toggle(self, task_id, payload):
        """Смена статуса (`POST /api/tasks/{id}/toggle`)."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        if not isinstance(payload, dict):
            raise BadRequestError("тело должно быть JSON-объектом с полем status")
        status = normalize_status(payload.get("status"))
        with self._lock:
            task = self._index.get(task_id)
            if task is None or task.get("deleted"):
                raise NotFoundError(task_id)
            now = time.time()
            status_changed = task.get("status") != status
            task["status"] = status
            task["updated_at"] = now
            if status == "completed":
                if task.get("completed_at") is None:
                    task["completed_at"] = now
            else:
                task["completed_at"] = None
            if status_changed:
                task["history"].append({"status": status, "ts": now})
            response = {"id": task["id"], "status": status, "updated_at": now}
        return response, "toggled status={0}".format(status)

    def delete(self, task_id):
        """Soft-delete задачи и всего поддерева (`DELETE /api/tasks/{id}`)."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        with self._lock:
            task = self._index.get(task_id)
            if task is None or task.get("deleted"):
                raise NotFoundError(task_id)
            now = time.time()
            subtree = self._subtree_ids_locked(task_id)
            for candidate in self._tasks:
                if candidate.get("id") in subtree:
                    candidate["deleted"] = 1
                    candidate["updated_at"] = now
            response = {"deleted": task_id}
        return response, "deleted tasks={0}".format(len(subtree))

    # --- вложения -------------------------------------------------------

    def add_attachment(self, task_id, orig_name, mime, data):
        """
        Аплоад вложения (`POST /api/tasks/{id}/attachments`), байты — в памяти.

        `updated_at` задачи не трогаем: аплоад не меняет версию задачи, и CAS
        после него остаётся прежним (клиенту не придётся перечитывать версию).
        """
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        with self._lock:
            task = self._index.get(task_id)
            if task is None or task.get("deleted"):
                raise NotFoundError(task_id)
            now = time.time()
            attachment = {
                "id": uuid.uuid4().hex,
                "task_id": task["id"],
                "orig_name": orig_name,
                "mime": mime,
                "size": len(data),
                "created_at": now,
                "deleted": 0,
            }
            task.setdefault("attachments", []).append(attachment)
            self._attachments[attachment["id"]] = (task, attachment)
            self._blobs[attachment["id"]] = data
            response = {"id": attachment["id"], "name": orig_name, "size": len(data)}
        summary = "attachment_upload name={0} size={1}".format(
            log_text(orig_name), len(data)
        )
        return response, summary

    def attachment_blob(self, attachment_id):
        """Байты вложения для `GET /api/attachments/{id}`: (вложение, байты)."""
        with self._lock:
            entry = self._attachments.get(attachment_id)
            if entry is None:
                raise NotFoundError(attachment_id)
            task, attachment = entry
            # Вложение удалённой задачи тоже недоступно: в `/api/state` его нет.
            if task.get("deleted") or attachment.get("deleted"):
                raise NotFoundError(attachment_id)
            stored = self._blobs.get(attachment_id)
            if stored is None:
                stored = _fixture_attachment_bytes(attachment)
            return dict(attachment), stored

    def delete_attachment(self, attachment_id):
        """Soft-delete вложения (`DELETE /api/attachments/{id}`)."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        with self._lock:
            entry = self._attachments.get(attachment_id)
            if entry is None:
                raise NotFoundError(attachment_id)
            task, attachment = entry
            if task.get("deleted") or attachment.get("deleted"):
                raise NotFoundError(attachment_id)
            attachment["deleted"] = 1
            self._blobs.pop(attachment_id, None)
        return {"deleted": attachment_id}, "attachment_deleted"

    # --- сотрудники -----------------------------------------------------

    def create_employee(self, payload):
        """`POST /api/employees`: имя обрезается по краям, пустое — 400."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        name = _employee_name(payload)
        with self._lock:
            employee = {"id": uuid.uuid4().hex, "name": name}
            self._employees.append(employee)
            self._employee_index[employee["id"]] = employee
            response = {"id": employee["id"], "name": employee["name"]}
        return response, "employee_created"

    def update_employee(self, employee_id, payload):
        """`PATCH /api/employees/{id}`: переименование карточки."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        name = _employee_name(payload)
        with self._lock:
            employee = self._employee_index.get(employee_id)
            if employee is None:
                raise NotFoundError(employee_id)
            employee["name"] = name
            response = dict(employee)
        return response, "employee_updated"

    def delete_employee(self, employee_id):
        """`DELETE /api/employees/{id}`: запись исчезает из `state.employees`."""
        if self._static:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        with self._lock:
            employee = self._employee_index.pop(employee_id, None)
            if employee is None:
                raise NotFoundError(employee_id)
            self._employees.remove(employee)
        return {"deleted": employee_id}, "employee_deleted"

    def _subtree_ids_locked(self, task_id):
        """Все потомки задачи (по parent_id) на любой глубине, включая её саму."""
        subtree = {task_id}
        changed = True
        while changed:
            changed = False
            for candidate in self._tasks:
                if candidate.get("parent_id") in subtree and candidate.get("id") not in subtree:
                    subtree.add(candidate.get("id"))
                    changed = True
        return subtree

    # --- вспомогательное ------------------------------------------------

    def _first_list_id(self):
        lists = self._fixture.doc.get("lists") or []
        if lists and isinstance(lists[0], dict):
            return lists[0].get("id") or ""
        return ""

    def _default_category_id(self):
        settings = self._fixture.doc.get("settings") or {}
        if isinstance(settings, dict):
            return settings.get("default_category")
        return None

    def write_state(self, path):
        """
        Флаг `--state-out`: пишет текущее состояние в файл атомарно.

        В файл идёт то же, что отдаёт `GET /api/state` (без soft-deleted задач),
        с текущим `server_time`. Сначала `*.tmp`, потом `os.replace`: читатель
        никогда не увидит половину JSON.
        """
        document, _, _ = self.current()
        if document is None:
            raise FixtureNotObjectError("фикстура не JSON-объект")
        target = os.path.abspath(path)
        directory = os.path.dirname(target) or os.curdir
        tmp_path = os.path.join(
            directory,
            ".{0}.{1}.tmp".format(os.path.basename(target), os.getpid()),
        )
        try:
            with open(tmp_path, "w", encoding="utf-8", newline="\n") as handle:
                json.dump(document, handle, ensure_ascii=False, indent=1)
                handle.write("\n")
            os.replace(tmp_path, target)
        finally:
            try:
                if os.path.exists(tmp_path):
                    os.remove(tmp_path)
            except OSError:
                pass


# --- эмуляция привязки устройства -----------------------------------------


class DeviceGate:
    """
    Эмуляция TOFU-привязки устройства.

    Счётчик общий на весь сервер (как и у настоящего сервера, который держит
    «ожидающие» устройства на своей стороне): первые `limit` запросов с валидным
    токеном получают 403 {"error": "device"}, остальные обслуживаются нормально.
    Перезапуск сервера сбрасывает счётчик.
    """

    def __init__(self, limit):
        self._lock = threading.Lock()
        self.limit = max(0, int(limit))
        self._remaining = self.limit

    def take_blocked(self):
        """True — этот запрос должен получить 403 (устройство «ждёт подтверждения»)."""
        with self._lock:
            if self._remaining <= 0:
                return False
            self._remaining -= 1
            return True

    def left(self):
        """Сколько ещё запросов получат 403 (число оставшихся «ожиданий»)."""
        with self._lock:
            return self._remaining


# --- HTTP-сервер -----------------------------------------------------------


class MockStateServer(ThreadingHTTPServer):
    """HTTP-сервер с настройками стенда; несколько соединений обслуживаются параллельно."""

    daemon_threads = True
    # POSIX: SO_REUSEADDR позволяет сразу перезапустить сервер после Ctrl+C
    # (мешают сокеты в TIME_WAIT), но порт, который уже кем-то слушается, не отдаёт.
    # Windows: SO_REUSEADDR наоборот позволяет двум процессам занять один порт,
    # и запросы молча уходят чужому серверу — там важнее строгий bind и громкая ошибка.
    allow_reuse_address = os.name != "nt"

    def __init__(self, address, handler_class, token, store, device_gate, state_out=None):
        super().__init__(address, handler_class)
        self.token = token
        self.store = store
        self.device_gate = device_gate
        self.state_out = state_out


def match_route(path):
    """
    Определяет маршрут по пути без query-строки.

    Возвращает (имя маршрута, параметры) или None для неизвестного пути.
    """
    if path == STATE_PATH:
        return "state", {}
    if path == TASKS_PATH:
        return "tasks", {}
    if path == EMPLOYEES_PATH:
        return "employees", {}
    toggle = TOGGLE_PATH_RE.match(path)
    if toggle:
        return "toggle", {"task_id": unquote(toggle.group("task_id"))}
    upload = ATTACH_UPLOAD_PATH_RE.match(path)
    if upload:
        return "attachment_upload", {"task_id": unquote(upload.group("task_id"))}
    attachment = ATTACHMENT_PATH_RE.match(path)
    if attachment:
        return "attachment", {"attachment_id": unquote(attachment.group("attachment_id"))}
    task = TASK_PATH_RE.match(path)
    if task:
        return "task", {"task_id": unquote(task.group("task_id"))}
    employee = EMPLOYEE_PATH_RE.match(path)
    if employee:
        return "employee", {"employee_id": unquote(employee.group("employee_id"))}
    return None


class MockStateHandler(BaseHTTPRequestHandler):
    """Обработчик `/api/state` и мутаций; всё остальное — 404/405 в виде JSON."""

    protocol_version = "HTTP/1.1"
    server_version = "TaskmanMockState/2.0"
    sys_version = ""
    #: Простаивающее keep-alive соединение не должно держать поток вечно.
    timeout = 60

    # --- маршрутизация ---------------------------------------------------

    def do_GET(self):  # noqa: N802 — имя задаёт http.server
        self._dispatch("GET", with_body=True)

    def do_HEAD(self):  # noqa: N802
        self._dispatch("HEAD", with_body=False)

    def do_POST(self):  # noqa: N802
        self._dispatch("POST", with_body=True)

    def do_DELETE(self):  # noqa: N802
        self._dispatch("DELETE", with_body=True)

    def do_PUT(self):  # noqa: N802
        self._dispatch("PUT", with_body=True)

    def do_PATCH(self):  # noqa: N802
        self._dispatch("PATCH", with_body=True)

    def do_OPTIONS(self):  # noqa: N802
        # Только для ручных проверок из браузера (CORS-предполёт). Для Android
        # не нужно, но стоит несколько строк и экономит нервы.
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header(*CORS_HEADER)
        self.send_header(
            "Access-Control-Allow-Methods", "GET, HEAD, POST, PATCH, DELETE, OPTIONS"
        )
        self.send_header(
            "Access-Control-Allow-Headers",
            "Token, X-Device-Id, If-None-Match, Content-Type",
        )
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()
        self._log(
            HTTPStatus.NO_CONTENT,
            is_304=False,
            token_state="n/a",
            gzip_enabled=False,
            sent=0,
            result="preflight",
        )

    # --- основная логика -------------------------------------------------

    def _dispatch(self, method, with_body):
        path, _, query = self.path.partition("?")
        route = match_route(path)

        # Тело читаем до любого ответа: иначе при отказе (401/403/404) без
        # вычитывания тела «поедет» keep-alive-соединение.
        try:
            body = self._read_body(self._body_limit())
        except PayloadTooLargeError:
            self._send_error(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "payload_too_large",
                with_body=with_body,
                token_state="n/a",
                result="body_too_large",
            )
            return
        except BadRequestError:
            self._send_error(
                HTTPStatus.BAD_REQUEST,
                "bad_request",
                with_body=with_body,
                token_state="n/a",
                result="bad_body",
            )
            return

        if route is None:
            # Неизвестный путь: токен не проверяем и счётчик «ожиданий» не трогаем.
            self._send_error(
                HTTPStatus.NOT_FOUND, "not_found", with_body=with_body, token_state="n/a"
            )
            return

        route_name, params = route
        allowed = ROUTE_METHODS[route_name]
        if method not in allowed:
            # Сверх контракта: по известному пути с чужим методом честнее 405.
            self._send_error(
                HTTPStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed",
                with_body=with_body,
                token_state="n/a",
                allow=", ".join(allowed + ("OPTIONS",)),
            )
            return

        # 1. Авторизация. Обычно только заголовок `Token`; `X-Token` игнорируется,
        #    как и на настоящем сервере (ТЗ §4). Исключение — скачивание
        #    вложения: там токен ТОЛЬКО query-параметром `?token=…`, заголовок
        #    `Token` на этом пути не читается (ТЗ §4).
        if route_name == "attachment" and method == "GET":
            if not self._query_token_matches(query):
                self._send_error(
                    HTTPStatus.UNAUTHORIZED,
                    "unauthorized",
                    with_body=with_body,
                    token_state="invalid",
                )
                return
            # Привязку устройства не расходуем: картинки грузятся без
            # X-Device-Id и без заголовка Token.
            self._handle_attachment_get(params["attachment_id"], with_body)
            return

        token_state = "ok" if self._token_matches() else "invalid"
        if token_state != "ok":
            self._send_error(
                HTTPStatus.UNAUTHORIZED,
                "unauthorized",
                with_body=with_body,
                token_state=token_state,
            )
            return

        # 2. Привязка устройства: пока «ждём подтверждения» — 403 device.
        #    Счётчик расходуют только запросы с валидным токеном.
        if self.server.device_gate.take_blocked():
            self._send_error(
                HTTPStatus.FORBIDDEN, "device", with_body=with_body, token_state="ok"
            )
            return

        # 3. Маршрут.
        if route_name == "state":
            self._handle_state(with_body)
        elif route_name == "tasks":
            self._handle_upsert(body, with_body)
        elif route_name == "toggle":
            self._handle_toggle(params["task_id"], body, with_body)
        elif route_name == "task":
            self._handle_delete(params["task_id"], with_body)
        elif route_name == "attachment_upload":
            self._handle_attachment_upload(params["task_id"], body, with_body)
        elif route_name == "attachment":
            self._handle_attachment_delete(params["attachment_id"], with_body)
        elif route_name == "employees":
            self._handle_employees_create(body, with_body)
        elif route_name == "employee":
            if method == "PATCH":
                self._handle_employee_update(params["employee_id"], body, with_body)
            else:
                self._handle_employee_delete(params["employee_id"], with_body)

    # --- обработчики маршрутов ------------------------------------------

    def _handle_state(self, with_body):
        _, raw, etag_value = self.server.store.current()
        etag = '"{0}"'.format(etag_value)
        if self._if_none_match_matches(etag):
            self._send_not_modified(etag)
            return
        self._send_body(HTTPStatus.OK, raw, etag=etag, with_body=with_body, result="state")

    def _handle_upsert(self, body, with_body):
        try:
            items = parse_json_body(body)
            response, summary = self.server.store.upsert(items)
        except ConflictError as exc:
            self._send_error(
                HTTPStatus.CONFLICT,
                "conflict",
                with_body=with_body,
                token_state="ok",
                extra={"actual_updated_at": exc.actual_updated_at},
                result="conflict",
            )
            return
        except BadRequestError:
            self._send_error(
                HTTPStatus.BAD_REQUEST,
                "bad_request",
                with_body=with_body,
                token_state="ok",
                result="bad_request",
            )
            return
        except FixtureNotObjectError:
            self._send_error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "fixture_not_object",
                with_body=with_body,
                token_state="ok",
                result="fixture_not_object",
            )
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result="upsert " + summary)

    def _handle_toggle(self, task_id, body, with_body):
        try:
            payload = parse_json_body(body)
            response, summary = self.server.store.toggle(task_id, payload)
        except NotFoundError:
            self._send_error(
                HTTPStatus.NOT_FOUND,
                "not_found",
                with_body=with_body,
                token_state="ok",
                result="not_found",
            )
            return
        except BadRequestError:
            self._send_error(
                HTTPStatus.BAD_REQUEST,
                "bad_request",
                with_body=with_body,
                token_state="ok",
                result="bad_request",
            )
            return
        except FixtureNotObjectError:
            self._send_error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "fixture_not_object",
                with_body=with_body,
                token_state="ok",
                result="fixture_not_object",
            )
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=summary)

    def _handle_delete(self, task_id, with_body):
        try:
            response, summary = self.server.store.delete(task_id)
        except NotFoundError:
            self._send_error(
                HTTPStatus.NOT_FOUND,
                "not_found",
                with_body=with_body,
                token_state="ok",
                result="not_found",
            )
            return
        except FixtureNotObjectError:
            self._send_error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "fixture_not_object",
                with_body=with_body,
                token_state="ok",
                result="fixture_not_object",
            )
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=summary)

    def _handle_attachment_upload(self, task_id, body, with_body):
        try:
            uploaded = parse_multipart_file(self.headers.get("Content-Type"), body)
            if len(uploaded.data) > MAX_ATTACHMENT_BYTES:
                raise PayloadTooLargeError(
                    "файл больше {0} байт".format(MAX_ATTACHMENT_BYTES)
                )
            response, result = self.server.store.add_attachment(
                task_id, uploaded.orig_name, uploaded.mime, uploaded.data
            )
        except (BadRequestError, NotFoundError, PayloadTooLargeError, FixtureNotObjectError) as exc:
            self._send_store_error(exc, with_body)
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=result)

    def _handle_attachment_get(self, attachment_id, with_body):
        try:
            attachment, data = self.server.store.attachment_blob(attachment_id)
        except NotFoundError:
            self._send_error(
                HTTPStatus.NOT_FOUND,
                "not_found",
                with_body=with_body,
                token_state="ok",
                result="attachment_missing",
            )
            return
        self._send_attachment(attachment, data, with_body=with_body)

    def _handle_attachment_delete(self, attachment_id, with_body):
        try:
            response, result = self.server.store.delete_attachment(attachment_id)
        except (NotFoundError, FixtureNotObjectError) as exc:
            self._send_store_error(exc, with_body)
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=result)

    def _handle_employees_create(self, body, with_body):
        try:
            payload = parse_json_body(body)
            response, result = self.server.store.create_employee(payload)
        except (BadRequestError, NotFoundError, FixtureNotObjectError) as exc:
            self._send_store_error(exc, with_body)
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=result)

    def _handle_employee_update(self, employee_id, body, with_body):
        try:
            payload = parse_json_body(body)
            response, result = self.server.store.update_employee(employee_id, payload)
        except (BadRequestError, NotFoundError, FixtureNotObjectError) as exc:
            self._send_store_error(exc, with_body)
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=result)

    def _handle_employee_delete(self, employee_id, with_body):
        try:
            response, result = self.server.store.delete_employee(employee_id)
        except (BadRequestError, NotFoundError, FixtureNotObjectError) as exc:
            self._send_store_error(exc, with_body)
            return
        self._save_state_out()
        self._send_json(HTTPStatus.OK, response, with_body=with_body, result=result)

    def _send_store_error(self, exc, with_body):
        """Общие отказы новых мутаций: 400/404/413/500 в формате контракта."""
        if isinstance(exc, BadRequestError):
            self._send_error(
                HTTPStatus.BAD_REQUEST,
                "bad_request",
                with_body=with_body,
                token_state="ok",
                result="bad_request",
            )
        elif isinstance(exc, NotFoundError):
            self._send_error(
                HTTPStatus.NOT_FOUND,
                "not_found",
                with_body=with_body,
                token_state="ok",
                result="not_found",
            )
        elif isinstance(exc, PayloadTooLargeError):
            self._send_error(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "payload_too_large",
                with_body=with_body,
                token_state="ok",
                result="attachment_too_large",
            )
        else:
            self._send_error(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                "fixture_not_object",
                with_body=with_body,
                token_state="ok",
                result="fixture_not_object",
            )

    def _save_state_out(self):
        """Пишет `--state-out` после мутации; ошибка файла не ломает ответ клиенту."""
        path = self.server.state_out
        if not path:
            return
        try:
            self.server.store.write_state(path)
        except (OSError, FixtureNotObjectError) as exc:
            sys.stderr.write(
                "[{0}] предупреждение: не удалось записать --state-out {1}: {2}\n".format(
                    time.strftime("%Y-%m-%d %H:%M:%S"), path, exc
                )
            )
            sys.stderr.flush()

    # --- проверки запроса ------------------------------------------------

    def _body_limit(self):
        """
        Потолок тела для текущего пути.

        Для аплоада 8 МиБ относится к самому файлу, поэтому multipart-обвязке
        (заголовки частей и boundary) разрешён небольшой запас.
        """
        route = match_route(self.path.partition("?")[0])
        if route is not None and route[0] == "attachment_upload":
            return MAX_ATTACHMENT_BYTES + MULTIPART_OVERHEAD_BYTES
        return MAX_BODY_BYTES

    def handle_expect_100(self):
        """
        `Expect: 100-continue` — так curl шлёт крупные тела.

        Если тела заведомо больше потолка, отвечаем 413 сразу, не дожидаясь
        его отправки. Иначе клиент получает промежуточный `100 Continue`, всё
        равно отправляет мегабайты, и в `-w %{http_code}` у curl видно `100`,
        а не финальный `413`.
        """
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length) if raw_length else 0
        except ValueError:
            length = 0
        if length > self._body_limit():
            self.close_connection = True
            self._send_error(
                HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                "payload_too_large",
                with_body=True,
                token_state="n/a",
                result="body_too_large",
            )
            return False
        return super().handle_expect_100()

    def _read_body(self, limit=MAX_BODY_BYTES):
        """
        Читает тело по `Content-Length`; без заголовка — пустое тело.

        Chunked-тела не поддерживаются: Retrofit/OkHttp и curl по умолчанию
        присылают Content-Length. Тело больше `limit` не вычитываем —
        соединение всё равно закрываем.
        """
        raw_length = self.headers.get("Content-Length")
        if not raw_length:
            return b""
        try:
            length = int(raw_length)
        except ValueError:
            self.close_connection = True
            raise BadRequestError("некорректный Content-Length")
        if length < 0:
            self.close_connection = True
            raise BadRequestError("отрицательный Content-Length")
        if length > limit:
            self.close_connection = True
            raise PayloadTooLargeError("тело больше {0} байт".format(limit))
        if length == 0:
            return b""
        data = self.rfile.read(length)
        if len(data) != length:
            self.close_connection = True
            raise BadRequestError("тело короче Content-Length")
        return data

    def _token_matches(self):
        """
        Сравнивает заголовок `Token` с ожидаемым значением.

        Имена заголовков в HTTP регистронезависимы, поэтому `token: ...` тоже
        подходит; важно, что это именно `Token`, а не `X-Token`.
        Сравнение не constant-time: это локальный dev-инструмент, а не защита.
        """
        provided = self.headers.get(HEADER_TOKEN)
        if not provided:
            return False
        return provided.strip() == self.server.token

    def _query_token_matches(self, query):
        """
        Проверяет токен из query-строки для `GET /api/attachments/{id}`.

        ТЗ §4: этот путь принимает токен только `?token=…`; заголовок `Token`
        здесь намеренно не читается, иначе клиент прошёл бы проверку не тем
        способом, чем нужно приложению.
        """
        if not query:
            return False
        values = parse_qs(query, keep_blank_values=True).get("token") or []
        return any(value.strip() == self.server.token for value in values)

    def _if_none_match_matches(self, etag):
        """Разбирает `If-None-Match`: список значений, `W/`-префикс и `*`."""
        raw = self.headers.get(HEADER_IF_NONE_MATCH)
        if not raw:
            return False
        for candidate in raw.split(","):
            candidate = candidate.strip()
            if candidate == "*":
                return True
            if candidate.startswith("W/"):
                candidate = candidate[2:].strip()
            if candidate == etag:
                return True
        return False

    def _wants_gzip(self):
        """Есть ли в `Accept-Encoding` допустимый gzip (учитывает `q=0`)."""
        header = self.headers.get(HEADER_ACCEPT_ENCODING)
        if not header:
            return False
        for item in header.split(","):
            parts = [part.strip() for part in item.split(";")]
            name = parts[0].lower()
            if name not in ("gzip", "x-gzip"):
                continue
            quality = 1.0
            for param in parts[1:]:
                if param.lower().startswith("q="):
                    try:
                        quality = float(param[2:])
                    except ValueError:
                        quality = 0.0
            if quality > 0:
                return True
        return False

    # --- отправка ответов ------------------------------------------------

    def _send_body(self, status, payload, etag=None, with_body=True, result=""):
        """Отправляет JSON-тело 200 (с ETag, gzip и CORS)."""
        use_gzip = self._wants_gzip()
        body = gzip.compress(payload) if use_gzip else payload
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        if etag:
            self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.send_header("Vary", "Accept-Encoding")
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        sent = self._write(body) if with_body else 0
        self._log(
            status,
            is_304=False,
            token_state="ok",
            gzip_enabled=use_gzip,
            sent=sent,
            result=result,
        )

    def _send_json(self, status, document, with_body=True, result=""):
        """Отправляет произвольный JSON-объект без ETag (ответы мутаций)."""
        payload = json.dumps(document, ensure_ascii=False).encode("utf-8")
        self._send_body(status, payload, with_body=with_body, result=result)

    def _send_not_modified(self, etag):
        # Тела у 304 нет, Content-Length не отправляем (RFC 7230 §3.3.2).
        self.send_response(HTTPStatus.NOT_MODIFIED)
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.end_headers()
        self._log(
            HTTPStatus.NOT_MODIFIED,
            is_304=True,
            token_state="ok",
            gzip_enabled=False,
            sent=0,
            result="not_modified",
        )

    def _send_attachment(self, attachment, data, with_body=True):
        """
        Отдаёт байты вложения (200): `Content-Type` из записи, `inline` и имя файла.

        gzip не применяем: это файл, а не JSON, и клиенту нужны ровно те байты,
        что были загружены. Значение `Content-Type` санируется: http.server
        кодирует заголовки в latin-1, кириллица там упала бы.
        """
        mime = _clean_header(attachment.get("mime")) or "application/octet-stream"
        disposition = content_disposition_inline(attachment.get("orig_name"))
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", disposition)
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.end_headers()
        sent = self._write(data) if with_body else 0
        self._log(
            HTTPStatus.OK,
            is_304=False,
            token_state="ok",
            gzip_enabled=False,
            sent=sent,
            result="attachment_get",
        )

    def _send_error(self, status, code, with_body, token_state, allow=None, extra=None, result=""):
        # json.dumps с разделителями по умолчанию даёт ровно {"error": "..."}.
        document = {"error": code}
        if extra:
            document.update(extra)
        payload = json.dumps(document).encode("utf-8")
        use_gzip = self._wants_gzip()
        if use_gzip:
            payload = gzip.compress(payload)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.send_header("Vary", "Accept-Encoding")
        if allow:
            self.send_header("Allow", allow)
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        sent = self._write(payload) if with_body else 0
        self._log(
            status,
            is_304=False,
            token_state=token_state,
            gzip_enabled=use_gzip,
            sent=sent,
            result=result or code,
        )

    def _write(self, payload):
        try:
            self.wfile.write(payload)
            return len(payload)
        except (BrokenPipeError, ConnectionResetError):
            # Клиент ушёл (например, закрыл curl) — это не ошибка сервера.
            self.close_connection = True
            return 0

    # --- журнал ----------------------------------------------------------

    def _loggable_path(self):
        """
        Путь для журнала: значение query-параметра `token` маскируется.

        Иначе `GET /api/attachments/{id}?token=…` напечатал бы токен в журнал,
        а значение токена не печатается никогда.
        """
        path, separator, query = self.path.partition("?")
        if not separator:
            return self.path
        masked = re.sub(r"(?i)(^|&)(token=)[^&]*", r"\1\2***", query)
        return "{0}?{1}".format(path, masked)

    def _log(self, status, is_304, token_state, gzip_enabled, sent, result=""):
        """
        Одна строка на запрос в stdout: метод, путь, код, признак 304,
        сколько «ожиданий» устройства осталось, состояние токена и результат
        мутации (created/updated/conflict/deleted/…).

        Значение токена не печатается никогда — только ok/invalid, а query
        `?token=` в пути маскируется.
        """
        device = "present" if self.headers.get(HEADER_DEVICE_ID) else "absent"
        tail = " result={0}".format(result) if result else ""
        print(
            "[{stamp}] {method} {path} -> {status} (304: {is304}) token={token} "
            "device_id={device} device_wait_left={waits} gzip={gzip} bytes_sent={sent}{tail}".format(
                stamp=time.strftime("%Y-%m-%d %H:%M:%S"),
                method=self.command,
                path=self._loggable_path(),
                status=int(status),
                is304="yes" if is_304 else "no",
                token=token_state,
                device=device,
                waits=self.server.device_gate.left(),
                gzip="yes" if gzip_enabled else "no",
                sent=sent,
                tail=tail,
            ),
            flush=True,
        )

    def log_request(self, code="-", size="-"):
        # Журнал запросов ведём сами в _log; стандартный отключён, чтобы не дублировать.
        return

    def log_message(self, fmt, *args):
        # Служебные сообщения http.server (ошибки разбора, обрывы) — в stderr,
        # чтобы не смешиваться с журналом запросов.
        sys.stderr.write("[{0}] {1}\n".format(time.strftime("%Y-%m-%d %H:%M:%S"), fmt % args))
        sys.stderr.flush()

    def version_string(self):
        # Без пустого хвоста от sys_version в заголовке Server.
        return self.server_version


# --- запуск и остановка ----------------------------------------------------


def _prepare_stdio():
    """
    Перенаправленный вывод в Windows иначе кодируется в cp1251 и может упасть
    на символах вне этой кодировки. В файл журнала пишем UTF-8.
    """
    for stream in (sys.stdout, sys.stderr):
        if stream is None:
            continue
        try:
            if stream.isatty():
                continue
        except (OSError, ValueError):
            continue
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (OSError, ValueError):
            pass


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="mock_state_server.py",
        description="Мок /api/state и мутаций задач на фикстуре. Только для локальной "
        "разработки, не сервер продукта.",
        epilog="Пример: python tools/mock-server/mock_state_server.py "
        "--port 18443 --device-gate 1 --state-out state.local.json",
    )
    parser.add_argument(
        "--host",
        default=DEFAULT_HOST,
        help="адрес прослушивания (по умолчанию {0})".format(DEFAULT_HOST),
    )
    parser.add_argument(
        "--port",
        type=int,
        default=DEFAULT_PORT,
        help="порт, 0 — любой свободный (по умолчанию {0})".format(DEFAULT_PORT),
    )
    parser.add_argument(
        "--token",
        default=DEFAULT_TOKEN,
        help="ожидаемое значение заголовка Token; значение по умолчанию — "
        "фиксированный dev-токен",
    )
    parser.add_argument(
        "--fixture",
        default=DEFAULT_FIXTURE,
        metavar="PATH",
        help="путь к JSON-фикстуре (по умолчанию fixtures/api/state.sample.json "
        "в корне репозитория)",
    )
    parser.add_argument(
        "--device-gate",
        type=int,
        default=0,
        metavar="N",
        help="первые N запросов с валидным токеном получают 403 device; "
        "0 — привязка выключена",
    )
    parser.add_argument(
        "--state-out",
        default=None,
        metavar="PATH",
        help="после каждой мутации сохранять текущее состояние в этот файл "
        "(по умолчанию не пишется)",
    )
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("--port должен быть в диапазоне 0..65535")
    if args.device_gate < 0:
        parser.error("--device-gate не может быть отрицательным")
    if not args.token:
        parser.error("--token не может быть пустым")
    return args


def print_banner(server):
    fixture = server.store.fixture
    host, port = server.server_address[0], server.server_address[1]
    _, _, initial_etag = server.store.current()
    print("=" * 78)
    print("  taskman mock state server  |  ТОЛЬКО ДЛЯ ЛОКАЛЬНОЙ РАЗРАБОТКИ")
    print("  Это не сервер продукта: состояние в памяти, перезапуск возвращает фикстуру.")
    print("=" * 78)
    print("  адрес       : http://{0}:{1}{2}".format(host, port, STATE_PATH))
    print("  фикстура    : {0}".format(fixture.path))
    print(
        "  в ней       : задач {0}, {1} байт, начальный ETag {2}".format(
            server.store.total_tasks(), len(fixture.raw), initial_etag
        )
    )
    print("  мутации     : POST {0} | POST {0}/{{id}}/toggle | DELETE {0}/{{id}}".format(TASKS_PATH))
    print(
        "  вложения    : POST /api/tasks/{id}/attachments (multipart, поле file)"
        " | GET /api/attachments/{id}?token=… | DELETE /api/attachments/{id}"
    )
    print("  сотрудники  : POST /api/employees | PATCH/DELETE /api/employees/{id}")
    print("  авторизация : заголовок Token (X-Token не принимается), значение не печатаем")
    if server.device_gate.limit:
        print(
            "  привязка    : ВКЛЮЧЕНА, первые {0} запросов с валидным токеном -> 403 device".format(
                server.device_gate.limit
            )
        )
    else:
        print("  привязка    : выключена (--device-gate 0)")
    if server.state_out:
        print("  state-out   : {0} (после каждой мутации)".format(server.state_out))
    else:
        print("  state-out   : выключен (--state-out PATH)")
    print("  эмулятор    : хост машины для Android-эмулятора — 10.0.2.2")
    print("  остановка   : Ctrl+C")
    print("=" * 78, flush=True)


def install_stop_handlers(stop_event):
    """
    Ставит обработчики Ctrl+C (SIGINT), а также SIGTERM/SIGBREAK, где они есть.

    Обработчик только поднимает флаг: вызывать `shutdown()` прямо из него нельзя —
    он ждёт завершения `serve_forever()` и на одном потоке это взаимоблокировка.
    """

    def request_stop(signum, frame):
        stop_event.set()

    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        signum = getattr(signal, name, None)
        if signum is None:
            continue
        try:
            signal.signal(signum, request_stop)
        except (OSError, ValueError):
            # Не на всех платформах сигнал доступен — не критично.
            continue


def run(server):
    """Обслуживает запросы до Ctrl+C и затем корректно закрывает сокет."""
    stop_event = threading.Event()
    install_stop_handlers(stop_event)

    worker = threading.Thread(target=server.serve_forever, name="mock-http", daemon=True)
    worker.start()
    print("сервер слушает; журнал запросов ниже", flush=True)
    try:
        # Короткий таймаут — чтобы обработчик сигнала точно успел выполниться.
        while not stop_event.wait(0.5):
            pass
    except KeyboardInterrupt:
        # Страховка, если SIGINT не удалось перехватить своим обработчиком.
        pass

    print("\nполучен сигнал остановки, закрываю сокет...", flush=True)
    server.shutdown()
    worker.join(timeout=5)
    server.server_close()
    print("сервер остановлен, порт освобождён", flush=True)


def main(argv=None):
    _prepare_stdio()
    args = parse_args(argv)

    try:
        fixture = StateFixture(args.fixture)
    except OSError as exc:
        print("Ошибка: не читается фикстура: {0}: {1}".format(args.fixture, exc), file=sys.stderr)
        print("Путь по умолчанию: {0}".format(DEFAULT_FIXTURE), file=sys.stderr)
        return 2
    except ValueError as exc:
        # json.JSONDecodeError — частный случай ValueError.
        print(
            "Ошибка: {0} — невалидный JSON: {1}".format(args.fixture, exc), file=sys.stderr
        )
        return 2
    if not isinstance(fixture.doc, dict):
        print(
            "Предупреждение: фикстура — не JSON-объект: GET отдаёт файл как раньше, "
            "мутации отвечают 500 fixture_not_object",
            file=sys.stderr,
        )

    state_out = None
    if args.state_out:
        state_out = os.path.abspath(args.state_out)
        parent = os.path.dirname(state_out) or os.curdir
        if not os.path.isdir(parent):
            print(
                "Ошибка: каталог для --state-out не существует: {0}".format(parent),
                file=sys.stderr,
            )
            return 2
        if os.path.isdir(state_out):
            print(
                "Ошибка: --state-out указывает на каталог, нужен путь к файлу: {0}".format(
                    state_out
                ),
                file=sys.stderr,
            )
            return 2

    store = StateStore(fixture)
    gate = DeviceGate(args.device_gate)
    try:
        server = MockStateServer(
            (args.host, args.port),
            MockStateHandler,
            token=args.token,
            store=store,
            device_gate=gate,
            state_out=state_out,
        )
    except OSError as exc:
        print(
            "Ошибка: не удалось занять {0}:{1} ({2}).".format(args.host, args.port, exc),
            file=sys.stderr,
        )
        print("Похоже, порт занят. Возьмите другой: --port 18443", file=sys.stderr)
        return 1

    print_banner(server)
    run(server)
    return 0


if __name__ == "__main__":
    sys.exit(main())
