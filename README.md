# EVA Backend

Kotlin/Ktor REST API для платформы ЕВА.

## Требования

- JDK 17+ (рекомендуется JBR из IntelliJ IDEA 2024.2.x)
- Docker (для PostgreSQL)
- IntelliJ IDEA (рекомендуется для сборки)

## Переменные окружения

| Переменная | Пример | Описание |
|------------|--------|----------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/eva_database` | JDBC URL |
| `DB_USER` | `eva_user` | Пользователь БД |
| `DB_PASSWORD` | `eva_secret_pass` | Пароль БД |
| `JWT_SECRET` | _(мин. 32 символа)_ | Секрет для подписи JWT |
| `YANDEX_API_KEY` | `AQVN...` | Ключ Yandex AI Studio для AI-анализа симптомов |
| `FCM_CREDENTIALS_PATH` | `/path/to/firebase.json` | Путь к Firebase service account |
| `UPLOAD_DIR` | `/app/uploads` | Директория для загруженных файлов |

Настраиваются в `src/main/resources/application.conf` (локально) или через переменные окружения (Docker).

## Запуск

```bash
# Запуск в режиме разработки
./gradlew run

# Сборка fat JAR
./gradlew shadowJar
java -jar build/libs/eva-backend-1.0.0-all.jar
```

Сервер стартует на **:8081**.

## База данных

Flyway автоматически применяет миграции при старте:

| Миграция | Содержание |
|----------|-----------|
| `V1__initial_schema.sql` | Полная схема: роли, пользователи, врачи, клиники, записи, симптомы, уведомления |
| `V2__appointment_reminders.sql` | Флаги `reminder_24h_sent`, `reminder_1h_sent` |
| `V3__password_reset_tokens.sql` | Таблица токенов сброса пароля |
| `V4__admin_doctor_support.sql` | Роль `doctor`, `doctors.user_id`, `is_hidden` для отзывов, индексы |
| `V5__logs_ip_varchar.sql` | IP-адрес в таблице logs → varchar |
| `V6__logs_meta_text.sql` | Meta-поле в таблице logs → text |
| `V7__enum_columns_to_text.sql` | Enum-колонки → text (совместимость Exposed) |
| `V8__clinic_logo.sql` | Поле `logo_url` в таблице clinics |
| `V9__fix_raw_response_type.sql` | Тип `raw_response` в ai_responses → text |
| `V10__ai_response_title.sql` | Поле `title` в ai_responses (AI-заголовок анализа) |

На существующей БД без истории Flyway первая миграция помечается как baseline (`baselineOnMigrate=true`).

## API

Базовый путь: `/api/v1/`

| Группа | Эндпоинты |
|--------|-----------|
| `/auth` | register, login, refresh, logout, me (GET/PATCH), photo (GET/POST), fcm-token, forgot-password, reset-password |
| `/doctors` | список (с фильтрами), поиск, детали, фото, отзывы (CRUD), can-review, избранное |
| `/clinics` | список клиник, логотип (POST/DELETE) |
| `/specializations` | список специализаций |
| `/schedules` | расписание врача, создание/удаление слотов |
| `/appointments` | запись, список, отмена, can-review, complete, conclusion |
| `/symptoms` | создать запрос, получить AI-ответ, история, `GET /quota` |
| `/notifications` | список, отметить прочитанным, детали |
| `/documents` | загрузка, список, скачивание, удаление |
| `/admin/users` | список, редактирование, смена роли, блокировка, фото (POST/DELETE), документы пользователя |
| `/admin/doctors` | CRUD, создание аккаунта, фото (POST/DELETE) |
| `/admin/clinics` | CRUD, логотип (DELETE) |
| `/admin/appointments` | список, смена статуса |
| `/admin/reviews` | модерация (скрыть/удалить) |
| `/admin/schedules` | расписание любого врача |
| `/admin/documents/{id}/download` | скачать документ пациента (admin) |
| `/doctor/appointments` | записи врача, заключения, документы пациента |
| `/doctor/documents/{id}/download` | скачать документ пациента (doctor) |
| `/health` | проверка состояния сервера |

## Ключевые сервисы

- **AuthService** — JWT (1ч) + Refresh-токены (30 дней), bcrypt
- **AiService** — Yandex AI Studio (Alice AI LLM) через Responses API, генерирует краткий заголовок + структурированный JSON-ответ на русском; принимает список доступных специализаций
- **NotificationService** — push-напоминания FCM каждые 5 минут (за 24ч и за 1ч до приёма)
- **FcmService** — Firebase Cloud Messaging, поддержка нескольких устройств

## Rate Limiting

| Лимитер | Предел | Применяется к |
|---------|--------|---------------|
| `ai_analyze` | 10 запросов / час / пользователь | `POST /symptoms/analyze` |
| `quota_check` | 120 запросов / час / пользователь | `GET /symptoms/quota` |

## Тестирование

```bash
./gradlew test --no-daemon
# Результат: 25 passed, 0 failed ✅
```

## Структура проекта

```
src/main/kotlin/com/eva/
  Application.kt            ← точка входа
  plugins/
    Databases.kt            ← Flyway + HikariCP + Exposed
    Routing.kt              ← регистрация роутов + планировщик напоминаний
    Security.kt             ← JWT-аутентификация, RBAC-хелперы
    HTTP.kt                 ← CORS, rate limiting (ai_analyze, quota_check)
  api/
    routes/
      Routes.kt             ← doctors, clinics, specs, schedules, symptoms
      AuthRoutes.kt         ← регистрация, вход, профиль, фото
      AppointmentRoutes.kt  ← записи пациента
      AdminRoutes.kt        ← admin-only CRUD
      DoctorRoutes.kt       ← doctor-only расписание и записи
      DocumentRoutes.kt     ← документы пациента
    dto/DTOs.kt             ← DTO классы
  data/
    tables/Tables.kt        ← Exposed ORM таблицы
    repository/             ← репозитории
  domain/models/Models.kt   ← доменные модели
  service/                  ← бизнес-логика
src/main/resources/
  application.conf          ← конфигурация Ktor
  db/migration/             ← Flyway SQL-миграции (V1–V10)
```
