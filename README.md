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
| `OPENAI_API_KEY` | `sk-...` | Ключ OpenAI для AI-анализа симптомов |
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
- `V1__initial_schema.sql` — полная схема (роли, пользователи, врачи, клиники, записи, и т.д.)
- `V2__appointment_reminders.sql` — флаги `reminder_24h_sent`, `reminder_1h_sent`

На существующей БД без истории Flyway первая миграция помечается как baseline (`baselineOnMigrate=true`).

## API

Базовый путь: `/api/v1/`

| Группа | Эндпоинты |
|--------|-----------|
| `/auth` | register, login, refresh, me (GET/PATCH), photo (GET/POST), fcm-token |
| `/doctors` | список, поиск, детали, отзывы |
| `/clinics` | список клиник |
| `/specializations` | список специализаций |
| `/schedules` | расписание врача |
| `/appointments` | запись, список, отмена, can-review |
| `/symptoms` | создать запрос, получить AI-ответ |
| `/notifications` | список, отметить прочитанным |
| `/documents` | загрузка, список, скачивание, удаление |
| `/health` | проверка состояния сервера |

## Ключевые сервисы

- **AuthService** — JWT (1ч) + Refresh-токены (30 дней), bcrypt
- **AiService** — OpenAI gpt-4o-mini, структурированный JSON-ответ на русском
- **NotificationService** — push-напоминания FCM каждые 5 минут (за 24ч и за 1ч до приёма)
- **FcmService** — Firebase Cloud Messaging, поддержка нескольких устройств

## Тестирование

```bash
JAVA_HOME="/d/IntelliJ IDEA Community Edition 2024.2.2/jbr" ./gradlew test --no-daemon
# Результат: 25 passed, 0 failed ✅
```

## Структура проекта

```
src/main/kotlin/com/eva/
  Application.kt            ← точка входа
  plugins/
    Databases.kt            ← Flyway + HikariCP + Exposed
    Routing.kt              ← регистрация роутов + планировщик напоминаний
    Security.kt             ← JWT-аутентификация
    HTTP.kt                 ← CORS, rate limiting
  api/
    routes/                 ← HTTP-обработчики
    dto/DTOs.kt             ← DTO классы
  data/
    tables/Tables.kt        ← Exposed ORM таблицы
    repository/             ← репозитории
  domain/models/Models.kt   ← доменные модели
  service/                  ← бизнес-логика
src/main/resources/
  application.conf          ← конфигурация Ktor
  db/migration/             ← Flyway SQL-миграции
```
