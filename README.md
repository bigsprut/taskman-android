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

```bash
# local.properties (gitignored):
#   tm.base.url=https://example.com:8444
./gradlew test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Токен доступа вводится в приложении при первом запуске и хранится в
Keystore. В репо секретов нет и не будет.

## Сервер

taskman-server — self-hosted бэкенд (FastAPI + SQLite, single-user
token-auth): задачи с подзадачами, статусы с историей, теги, категории,
вложения, офлайн-first клиенты. Контракт — в ТЗ, раздел 6. Свой инстанс
поднимается одним Python-файлом; параметры подключения клиент берёт из
`local.properties`.

## Лицензия

MIT — см. [LICENSE](LICENSE).
