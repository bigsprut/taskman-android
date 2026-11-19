# PLAN — Ф0: каркас + вход + список (read-only)

Ветка: `phase/0-skeleton` · Источник требований: `TZ-01-android-app.md` (§5.1–5.4, §6, §9).

## 1. Цель фазы и критерий приёмки

Собирается APK одной командой из чистого клона; вход с реальным токеном; экран
ожидания device-подтверждения переживает 403 и сам разворачивается в список;
список = набору фикстуры (50+ строк, сортировка `STATUS_ORDER`); тёмная/светлая
тема, поворот, predictive back — без развалов (§9, Ф0).

## 2. Версии (фиксируются результатом сборки)

| Компонент | Версия | Почему |
|---|---|---|
| Gradle | 9.6.0 | минимум и default для AGP 9.5 |
| AGP | 9.5.1 | стабильный; максимальная поддерживаемая API — 37 |
| JDK | 17 (Zulu 17) | минимум AGP 9.5; системная Java 11 не годится |
| Kotlin | built-in Kotlin (AGP 9), KGP на buildscript-классpath | см. §8 «Заметки о сборке» |
| compileSdk / targetSdk | 37 | §5.1 |
| minSdk | 26 | §5.1 |
| Compose BOM | 2026.09.00 (Compose UI 1.12.1, M3 1.4.0) | последний стабильный набор, совместимый с compileSdk 37; Compose 1.13 требует SDK 37.1 |
| Room / KSP | 2.8.5 / см. libs.versions.toml | локальный источник UI-правды (§5.1) |
| Retrofit / OkHttp | 3.0.0 / 5.5.1 | стек §5.1 |
| kotlinx.serialization | 1.13.0 | JSON-контракт §6 |
| DataStore | 1.3.0 | токен/URL/device-id (§5.4) |

## 3. Архитектура (single-module `:app`, manual DI)

```
TaskmanApp (Application) → AppContainer (ручной DI: settings, net, db, repo)
MainActivity → Compose NavHost (login / device / tasks / settings)
data/
  settings/  SettingsStore (DataStore+Keystore),  SecureTokenCodec (AES/GCM)
  remote/    TaskmanApi (Retrofit), DTO (§6), NetworkFactory (OkHttp+Cache),
             ApiFailure/ошибки контракта (401/403 device/409/4xx/IOException)
  local/     Room: сущности state, DAO, AppDatabase
  repo/      TaskRepository: /api/state → Room (атомарно) → Flow<domain> (сортировка)
domain/      Task, TaskStatus (+legacy needsAction), StatusOrder, AppSettings
ui/          theme (M3, dynamic color, тёмная/светлая), screens, components, VM
```

Обоснование manual DI: одна фаза, один модуль, нет аннотационных процессоров в DI —
меньше сборочной магии; Hilt добавим только при реальной потребности (Ф4+).
Решение и версии фиксируются в `PHASE-REPORT.md`.

## 4. Нарезка подзадач

- [x] **П1. Сборка и каркас проекта** — settings/build-скрипты, version catalog,
      wrapper 9.6.0, `app`-модуль, `local.properties` (gitignored) → BuildConfig
      `TM_BASE_URL` (placeholder `https://example.com:8444`), манифест
      (`enableOnBackInvokedCallback`, `allowBackup=false`), splash, adaptive icon.
- [x] **П2. Тема и каркас UI** — M3 тема (dynamic color ≥31 + свои схемы),
      edge-to-edge + `safeDrawing` insets, `Scaffold`/`TopAppBar`, `NavigationBar`
      (Список / Настройки), splash-screen API, состояния Loading/Empty/Error/Auth.
- [x] **П3. Конфиг и секреты** — DataStore (base URL, device UUID), токен в
      DataStore в шифротексте AES/GCM, ключ в AndroidKeyStore; сброс подключения.
- [x] **П4. Сеть по контракту §6** — OkHttp (`Token`, `X-Device-Id`, `Cache`
      для ETag/304 gzip), Retrofit + kotlinx.serialization, DTO `/api/state`,
      маппинг ошибок: 401 → вход, 403 `{"error":"device"}` → ожидание, 409 →
      конфликт, прочие 4xx → тост с кодом, IOException → сеть.
- [x] **П5. Room-кэш** — сущности списка/категорий/тегов/сотрудников/задач/
      подзадач/вложений/истории; транзакционная замена снапшота; без потери
      данных при повторном синке.
- [x] **П6. Репозиторий и сортировка** — синхронизация с Mutex, `STATUS_ORDER`
      (inprogress → created → completed → canceled), внутри группы — свежесть
      даты статуса; единая точка правды для сортировки (тестируемая).
- [x] **П7. Экраны Ф0** — вход (base URL + токен), ожидание подтверждения
      устройства (авторетрай 30 с + «Повторить»), список read-only
      (pull-to-refresh), минимальные настройки (адрес, device id, сброс).
- [x] **П8. Тесты** — парсер фикстуры, маппинг статусов/legacy, сортировка,
      ошибки контракта, синк «API → Room»; JVM, `./gradlew test` зелёный.
- [x] **П9. Документы** — `PHASE-REPORT.md` (что сделано, версии, результаты
      `test`/`assembleDebug`, число тестов), `QUESTIONS.md` при вопросах, push ветки.

## 5. Что НЕ делаем в Ф0

Мутации (чекбокс/переименование/удаление), офлайн-очередь, вложения, редактор,
фильтры, CRUD справочников — это Ф1–Ф5. Сервер не правим: расхождения — в
`PHASE-REPORT.md` разделом «server» (+ issue при необходимости).

## 6. Риски и как проверяем

| Риск | Проверка / запасной путь |
|---|---|
| AGP 9 built-in Kotlin: какие compiler-plugin'ы и KSP совместимы | пробная сборка на старте; версии KGP/KSP фиксируются по факту (см. §2) |
| compileSdk 37 на AGP 9.5 | поддерживается (макс. API 37), build-tools 36.0.0 |
| Системный JDK 11 | `JAVA_HOME` на Zulu 17 в командах сборки; отражено в README/отчёте |
| Секрет в логах/кэше | токен не логируется, HTTP-логирование не включаем; `allowBackup=false` |
| TOFU необратим на сервере | в приложении постоянный UUID из DataStore; против общего сервера не гоняем |

## 7. Definition of Done

1. `./gradlew test assembleDebug` зелёный на JDK 17 (число тестов зафиксировано).
2. Список на фикстуре: 51 задача, порядок `STATUS_ORDER`.
3. 403 `device` → экран ожидания с авторетраем 30 с; смена токена/URL без
   переустановки (экран входа доступен).
4. `PHASE-REPORT.md` + push ветки `phase/0-skeleton`; PR — после ревью.
