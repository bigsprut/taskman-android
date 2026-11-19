# taskman-android

Нативный Android-клиент (Kotlin + Jetpack Compose, Material 3) для self-hosted
бэкенда **taskman** (FastAPI + SQLite). Целевое устройство: OnePlus 15,
Android 17 (API 37).

Продукт в активной разработке (фазы Ф0–Ф5, см. ТЗ). Контрибьюции — welcome,
см. [CONTRIBUTING.md](CONTRIBUTING.md).

## Документы

- **[TZ-01-android-app.md](TZ-01-android-app.md)** — техническое задание
  (фазы, полный контракт API сервера, стандарты, запреты). Начинать с него.
- `fixtures/api/state.sample.json` — эталонный ответ `GET /api/state`
  (санитизированные живые данные). Эталон парсера, менять нельзя.

## Сборка

Нужен **JDK 17** (системная Java 11 не подходит: AGP 9.5 требует 17) и Android SDK с
платформой `android-37.0` и build-tools `36.0.0`.

`local.properties` в git не попадает; в нём путь к SDK и адрес своего инстанса:

```properties
sdk.dir=D\:\\Android\\SDK
tm.base.url=https://example.com:8444
```

Адрес из `tm.base.url` подставляется в `BuildConfig.TM_BASE_URL` и служит значением по
умолчанию на экране входа — введённый в приложении адрес сохраняется на устройстве.
Без `local.properties` сборка тоже работает: подставляется placeholder из ТЗ.

```bash
# Windows (PowerShell): JDK 17 из Zulu
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-17'

./gradlew test assembleDebug          # тесты + debug APK
./gradlew :app:lintDebug              # статический анализ
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Локальный стенд для разработки без живого сервера — мок на эталонной фикстуре:
`python tools/mock-server/mock_state_server.py --port 8443 --token local-dev-token`
(подробности — в `tools/mock-server/README.md`; это инструмент разработки, не сервер продукта).

Токен доступа вводится в приложении при первом запуске и хранится в Keystore. В репо
секретов нет и не будет.

## Сервер

taskman-server — self-hosted бэкенд (FastAPI + SQLite, single-user
token-auth): задачи с подзадачами, статусы с историей, теги, категории,
вложения, офлайн-first клиенты. Контракт — в ТЗ, раздел 6. Свой инстанс
поднимается одним Python-файлом; параметры подключения клиент берёт из
`local.properties`.

## Лицензия

MIT — см. [LICENSE](LICENSE).
