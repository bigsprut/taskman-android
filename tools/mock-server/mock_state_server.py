#!/usr/bin/env python3
"""
Мок-сервер состояния taskman для локальной разработки.

ЭТО НЕ СЕРВЕР ПРОДУКТА И НЕ ЭМУЛЯТОР БЭКЕНДА ЦЕЛИКОМ. Он отдаёт одну статичную
фикстуру `fixtures/api/state.sample.json` по `GET /api/state`, чтобы приложение
можно было запускать и проверять руками без доступа к настоящему серверу
владельца. Запись (POST/PUT/DELETE), вложения и живые данные не поддерживаются.
Значения по умолчанию (адрес 127.0.0.1, порт 8443, токен) — только для локального
стенда, в продукт их переносить нельзя.

Реализация — только стандартная библиотека Python 3, pip-зависимостей нет.

Что проверяется этим сервером (контракт из ТЗ §4):
  * авторизация заголовком `Token` (именно `Token`; `X-Token` не принимается),
    иначе 401 {"error": "unauthorized"};
  * эмуляция привязки устройства (TOFU): первые N запросов с валидным токеном
    отвечают 403 {"error": "device"}, далее сервер работает нормально
    (флаг --device-gate N, по умолчанию выключено);
  * `ETag: "<md5>"` — md5 канонического JSON без поля `server_time`;
  * `If-None-Match` совпал с ETag — 304 без тела;
  * `Accept-Encoding: gzip` — сжатое тело и `Content-Encoding: gzip`;
  * `Cache-Control: no-cache`, `Access-Control-Allow-Origin: *`;
  * остальные пути — 404 {"error": "not_found"}.

Запуск (из корня репозитория):
    python tools/mock-server/mock_state_server.py
    python tools/mock-server/mock_state_server.py --port 18443 --device-gate 1

Остановка — Ctrl+C.
"""

import argparse
import gzip
import hashlib
import json
import os
import signal
import sys
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --- значения по умолчанию -------------------------------------------------

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8443
DEFAULT_TOKEN = "local-dev-token"

#: Единственный поддерживаемый путь API.
STATE_PATH = "/api/state"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DEFAULT_FIXTURE = os.path.join(REPO_ROOT, "fixtures", "api", "state.sample.json")

HEADER_TOKEN = "Token"
HEADER_IF_NONE_MATCH = "If-None-Match"
HEADER_ACCEPT_ENCODING = "Accept-Encoding"
HEADER_DEVICE_ID = "X-Device-Id"

#: Заголовки, которые сервер отдаёт вместе с телом (кроме ETag у ошибок).
CORS_HEADER = ("Access-Control-Allow-Origin", "*")


# --- фикстура и ETag -------------------------------------------------------


def compute_etag(document):
    """
    Считает ETag тела ответа: md5 канонического JSON без поля `server_time`.

    `server_time` исключается намеренно: настоящий сервер меняет его от запроса
    к запросу, и если бы он входил в ETag, ответ 304 не случился бы никогда.

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


class StateFixture:
    """Загруженная фикстура: байты тела, разобранный JSON, ETag и статистика."""

    def __init__(self, path):
        self.path = os.path.abspath(path)
        with open(self.path, "rb") as handle:
            self.raw = handle.read()
        # utf-8-sig — на случай BOM в начале файла; отдаём при этом ровно те
        # байты, которые лежат на диске.
        self.doc = json.loads(self.raw.decode("utf-8-sig"))
        self.etag = compute_etag(self.doc)
        self.tasks = len(self.doc.get("tasks") or []) if isinstance(self.doc, dict) else 0


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

    def __init__(self, address, handler_class, token, fixture, device_gate):
        super().__init__(address, handler_class)
        self.token = token
        self.fixture = fixture
        self.device_gate = device_gate


class MockStateHandler(BaseHTTPRequestHandler):
    """Обработчик `GET /api/state`; всё остальное — 404/405 в виде JSON."""

    protocol_version = "HTTP/1.1"
    server_version = "TaskmanMockState/1.0"
    sys_version = ""
    #: Простаивающее keep-alive соединение не должно держать поток вечно.
    timeout = 60

    # --- маршрутизация ---------------------------------------------------

    def do_GET(self):  # noqa: N802 — имя задаёт http.server
        self._handle(with_body=True)

    def do_HEAD(self):  # noqa: N802
        self._handle(with_body=False)

    def do_OPTIONS(self):  # noqa: N802
        # Только для ручных проверок из браузера (CORS-предполёт). Для Android
        # не нужно, но стоит шесть строк и экономит нервы.
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header(
            "Access-Control-Allow-Headers",
            "Token, X-Device-Id, If-None-Match, Content-Type",
        )
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()
        self._log(HTTPStatus.NO_CONTENT, is_304=False, token_state="n/a", gzip_enabled=False, sent=0)

    def do_POST(self):  # noqa: N802
        self._method_not_allowed(with_body=True)

    def do_PUT(self):  # noqa: N802
        self._method_not_allowed(with_body=True)

    def do_PATCH(self):  # noqa: N802
        self._method_not_allowed(with_body=True)

    def do_DELETE(self):  # noqa: N802
        self._method_not_allowed(with_body=True)

    # --- основная логика -------------------------------------------------

    def _handle(self, with_body):
        path = self.path.split("?", 1)[0]
        if path != STATE_PATH:
            # Неизвестный путь: токен не проверяем и счётчик «ожиданий» не трогаем.
            self._send_error(
                HTTPStatus.NOT_FOUND, "not_found", with_body=with_body, token_state="n/a"
            )
            return

        # 1. Авторизация. Только заголовок `Token`; `X-Token` игнорируется,
        #    как и на настоящем сервере (ТЗ §4).
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

        # 3. Условный запрос: ETag совпал — 304 без тела.
        etag = '"{0}"'.format(self.server.fixture.etag)
        if self._if_none_match_matches(etag):
            self._send_not_modified(etag)
            return

        # 4. Обычный ответ с телом фикстуры.
        self._send_fixture(etag, with_body=with_body)

    def _method_not_allowed(self, with_body):
        # Сверх описанного контракта (там только GET и 404 на прочие пути):
        # по известному пути с чужим методом честнее ответить 405, а не 404.
        self._send_error(
            HTTPStatus.METHOD_NOT_ALLOWED,
            "method_not_allowed",
            with_body=with_body,
            token_state="n/a",
            allow="GET, HEAD, OPTIONS",
        )

    # --- проверки запроса ------------------------------------------------

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

    def _send_fixture(self, etag, with_body):
        fixture = self.server.fixture
        use_gzip = self._wants_gzip()
        payload = gzip.compress(fixture.raw) if use_gzip else fixture.raw
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.send_header("Vary", "Accept-Encoding")
        if use_gzip:
            self.send_header("Content-Encoding", "gzip")
        self.end_headers()
        sent = self._write(payload) if with_body else 0
        self._log(
            HTTPStatus.OK,
            is_304=False,
            token_state="ok",
            gzip_enabled=use_gzip,
            sent=sent,
        )

    def _send_not_modified(self, etag):
        # Тела у 304 нет, Content-Length не отправляем (RFC 7230 §3.3.2).
        self.send_response(HTTPStatus.NOT_MODIFIED)
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.send_header(*CORS_HEADER)
        self.end_headers()
        self._log(HTTPStatus.NOT_MODIFIED, is_304=True, token_state="ok", gzip_enabled=False, sent=0)

    def _send_error(self, status, code, with_body, token_state, allow=None):
        # json.dumps с разделителями по умолчанию даёт ровно {"error": "..."}.
        payload = json.dumps({"error": code}).encode("utf-8")
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
        self._log(status, is_304=False, token_state=token_state, gzip_enabled=use_gzip, sent=sent)

    def _write(self, payload):
        try:
            self.wfile.write(payload)
            return len(payload)
        except (BrokenPipeError, ConnectionResetError):
            # Клиент ушёл (например, закрыл curl) — это не ошибка сервера.
            self.close_connection = True
            return 0

    # --- журнал ----------------------------------------------------------

    def _log(self, status, is_304, token_state, gzip_enabled, sent):
        """
        Одна строка на запрос в stdout: метод, путь, код, признак 304,
        сколько «ожиданий» устройства осталось, состояние токена.

        Значение токена не печатается никогда — только ok/invalid.
        """
        device = "present" if self.headers.get(HEADER_DEVICE_ID) else "absent"
        print(
            "[{stamp}] {method} {path} -> {status} (304: {is304}) token={token} "
            "device_id={device} device_wait_left={waits} gzip={gzip} bytes_sent={sent}".format(
                stamp=time.strftime("%Y-%m-%d %H:%M:%S"),
                method=self.command,
                path=self.path,
                status=int(status),
                is304="yes" if is_304 else "no",
                token=token_state,
                device=device,
                waits=self.server.device_gate.left(),
                gzip="yes" if gzip_enabled else "no",
                sent=sent,
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
        description="Мок GET /api/state на фикстуре. Только для локальной разработки, "
        "не сервер продукта.",
        epilog="Пример: python tools/mock-server/mock_state_server.py "
        "--port 18443 --device-gate 1",
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
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535:
        parser.error("--port должен быть в диапазоне 0..65535")
    if args.device_gate < 0:
        parser.error("--device-gate не может быть отрицательным")
    if not args.token:
        parser.error("--token не может быть пустым")
    return args


def print_banner(server):
    fixture = server.fixture
    host, port = server.server_address[0], server.server_address[1]
    print("=" * 78)
    print("  taskman mock state server  |  ТОЛЬКО ДЛЯ ЛОКАЛЬНОЙ РАЗРАБОТКИ")
    print("  Это не сервер продукта: данные статичны, запись не поддерживается.")
    print("=" * 78)
    print("  адрес       : http://{0}:{1}{2}".format(host, port, STATE_PATH))
    print("  фикстура    : {0}".format(fixture.path))
    print(
        "  в ней       : задач {0}, {1} байт, ETag {2}".format(
            fixture.tasks, len(fixture.raw), fixture.etag
        )
    )
    print("  авторизация : заголовок Token (X-Token не принимается), значение не печатаем")
    if server.device_gate.limit:
        print(
            "  привязка    : ВКЛЮЧЕНА, первые {0} запросов с валидным токеном -> 403 device".format(
                server.device_gate.limit
            )
        )
    else:
        print("  привязка    : выключена (--device-gate 0)")
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
            "Предупреждение: фикстура — не JSON-объект, ETag считается по всему документу",
            file=sys.stderr,
        )

    gate = DeviceGate(args.device_gate)
    try:
        server = MockStateServer(
            (args.host, args.port),
            MockStateHandler,
            token=args.token,
            fixture=fixture,
            device_gate=gate,
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
