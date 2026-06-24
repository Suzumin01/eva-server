<div align="center">
  <h1>EVA Backend</h1>
  <p>REST API сервер платформы ЕВА — Единый Врачебный Ассистент</p>

  ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
  ![Ktor](https://img.shields.io/badge/Ktor-087CFA?logo=ktor&logoColor=white)
  ![PostgreSQL](https://img.shields.io/badge/PostgreSQL_15-336791?logo=postgresql&logoColor=white)
  ![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)
</div>

---

## Возможности

- JWT-аутентификация (access 1ч + refresh 30 дней), bcrypt
- AI-анализ симптомов через Yandex AI Studio (Alice LLM): диагноз, рекомендации, уровень срочности
- Push-уведомления FCM с напоминаниями за 24ч и 1ч до приёма
- Загрузка фото и медицинских документов
- Отзывы с автоматическим пересчётом рейтинга врача через DB-триггер
- Rate limiting, RBAC (пациент / врач / администратор)

## Быстрый старт

**1. База данных**
```bash
cd ../eva_bd && docker-compose up -d
```

**2. Переменные окружения**

| Переменная | Пример |
|------------|--------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/eva_database` |
| `DB_USER` | `eva_user` |
| `DB_PASSWORD` | `eva_secret_pass` |
| `JWT_SECRET` | _(минимум 32 символа)_ |
| `YANDEX_API_KEY` | ключ Yandex AI Studio |
| `FCM_CREDENTIALS_PATH` | путь к `firebase-service-account.json` |
| `UPLOAD_DIR` | директория для файлов, по умолчанию `./uploads` |

**3. Запуск**
```bash
./gradlew run          # dev-режим на :8081
./gradlew shadowJar    # fat JAR → build/libs/
```

## API

Базовый путь: `/api/v1/`

| Группа | Эндпоинты |
|--------|-----------|
| `/auth` | register, login, refresh, logout, me (GET/PATCH), photo, fcm-token, forgot/reset-password |
| `/doctors` | список (с фильтрами), поиск, детали, фото, отзывы (CRUD), can-review, избранное |
| `/clinics` | список, логотип |
| `/specializations` | список |
| `/schedules` | расписание врача, создание/удаление слотов |
| `/appointments` | запись, список, отмена, complete, conclusion |
| `/symptoms` | анализ (AI), история, квота |
| `/notifications` | список, прочитать |
| `/documents` | загрузка, список, скачивание, удаление |
| `/admin` | CRUD пользователей, врачей, клиник, записей, модерация отзывов |
| `/doctor` | расписание и записи врача, документы пациента |
| `/health` | health check |

## База данных

Flyway применяет миграции автоматически при старте:

| Миграция | Содержание |
|----------|------------|
| `V1` | Полная схема: пользователи, врачи, клиники, записи, симптомы, уведомления |
| `V2` | Флаги напоминаний `reminder_24h_sent`, `reminder_1h_sent` |
| `V3` | Таблица токенов сброса пароля |
| `V4` | Роль `doctor`, `doctors.user_id`, `is_hidden` для отзывов, индексы |
| `V5–V6` | Правки типов в таблице logs |
| `V7` | Enum-колонки → text (совместимость Exposed ORM) |
| `V8` | `logo_url` в clinics |
| `V9` | Тип `raw_response` в ai_responses |
| `V10` | `title` в ai_responses (AI-заголовок анализа) |

## Структура проекта

```
src/main/kotlin/com/eva/
  plugins/          ← CORS, JWT, HikariCP + Flyway, Routing, RateLimit
  api/routes/       ← HTTP-обработчики
  api/dto/          ← Request/Response DTO
  data/tables/      ← Exposed ORM таблицы
  data/repository/  ← Репозитории
  service/          ← AiService, AuthService, FcmService, NotificationService
src/main/resources/
  application.conf  ← конфигурация Ktor
  db/migration/     ← Flyway SQL-миграции (V1–V10)
```

## Тестирование

```bash
./gradlew test
# 28 passed ✅
```
