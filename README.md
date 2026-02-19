# Spring AI SnowOne

Демо-приложение на **Spring Boot 3.5.8** + **Spring AI 1.1.2**, реализующее AI-ассистента для Java-разработчиков с RAG, вызовом инструментов (Tool Calling), MCP-интеграцией, гардрейлами и чат-интерфейсом на Vaadin.

## Возможности

- **Чат-интерфейс** — веб-UI на Vaadin с поддержкой push-обновлений.
- **REST API** — эндпоинты для чата, демо-запроса и очистки истории.
- **Память диалога** — история сообщений сохраняется в рамках сессии.
- **Tool Calling** — ThinkingTool для пошагового рассуждения модели перед действием.
- **MCP (Model Context Protocol)** — внешние инструменты: отправка macOS-уведомлений и создание GitHub Gist (подробнее в разделе [MCP-серверы](#mcp-серверы)).
- **RAG (Retrieval-Augmented Generation)** — автоматическая загрузка и индексация вопросов с ответами со Stack Overflow в векторное хранилище pgvector. При первом запуске загружается до 1000 самых популярных вопросов по Java, при последующих — подгружаются свежие вопросы.
- **Гардрейлы** — LLM-валидация входящих сообщений: допускаются только вопросы по Java/Spring/JVM, остальные темы отклоняются.
- **Observability** — Prometheus + Grafana (готовые дашборды) + Jaeger (распределённые трейсы через OpenTelemetry).

## Стек технологий

| Компонент | Технология | Версия |
|---|---|---|
| Язык | Java | 21 |
| Фреймворк | Spring Boot | 3.5.8 |
| AI-фреймворк | Spring AI | 1.1.2 |
| UI | Vaadin | 24.6.2 |
| Векторная БД | PostgreSQL + pgvector | 16 |
| Метрики | Prometheus | 2.53.0 |
| Дашборды | Grafana | 11.1.0 |
| Трейсинг | Jaeger (OTLP) | 1.58 |
| Сборка | Maven | — |

## Быстрый старт

### Требования

- Java 21+
- Maven
- Docker + Docker Compose
- [Ollama](https://ollama.ai) (модели подтягиваются автоматически при первом запуске)

### Запуск

```bash
# 1. Поднять инфраструктуру (PostgreSQL/pgvector, Prometheus, Grafana, Jaeger)
docker-compose up -d

# 2. Собрать проект
mvn clean package

# 3. Запустить приложение
mvn spring-boot:run
```

Браузер откроется автоматически на http://localhost:8080.

### Переменные окружения

| Переменная | Назначение | Обязательна |
|---|---|---|
| `GITHUB_GIST_TOKEN` | GitHub Gist MCP-сервер | Да (для создания гистов) |
| `MCP_GIST_SERVER_PATH` | Путь до `build/index.js` mcp-server-gist | Да (для создания гистов) |
| `OPENROUTER_API_KEY` | Профиль `chat-openrouter` | Только при использовании OpenRouter |
| `GIGACHAT_API_KEY` | Профиль `gigachat` | Только при использовании GigaChat |
| `GIGACHAT_API_SCOPE` | Профиль `gigachat` | Только при использовании GigaChat |

## Профили конфигурации

По умолчанию активны профили `chat-ollama,embedding-ollama`.

| Профиль | Модель чата | Модель эмбеддингов | Описание |
|---|---|---|---|
| `chat-ollama` | `qwen3-coder:30b` | — | Локальная модель через Ollama (по умолчанию) |
| `chat-openrouter` | `mistralai/devstral-2512:free` | — | OpenRouter API (бесплатный тариф) |
| `embedding-ollama` | — | `nomic-embed-text:v1.5` | Локальные эмбеддинги через Ollama (по умолчанию) |
| `gigachat` | `GigaChat-2-Max` | `Embeddings-2` | GigaChat API (чат + эмбеддинги) |

Переключение на OpenRouter:
```yaml
spring.profiles.active: chat-openrouter,embedding-ollama
```

Переключение на GigaChat:
```yaml
spring.profiles.active: gigachat
```

## Архитектура

```
ChatView (Vaadin UI) / ChatController (REST)
        │
        ▼
    ChatService
        │
        ├── InputMessageValidatorAdvisor  ← гардрейл (LLM-валидация темы)
        ├── MessageChatMemoryAdvisor      ← память диалога
        ├── QuestionAnswerAdvisor (RAG)   ← контекст из векторного хранилища
        │       └── ParentDocumentRetriever
        │               └── VectorStore (pgvector)
        │                       └── VectorStoreInitializer
        │                               └── StackOverflowApi
        ├── ThinkingTool                  ← инструмент рассуждений
        └── MCP Tools                    ← macOS-уведомления, GitHub Gist
```

## REST API

| Метод | Путь | Описание |
|---|---|---|
| POST | `/test` | Демо-запрос: генерация примера кода Spring AI и сохранение в Gist |
| POST | `/chat` | Произвольное сообщение (текст в теле запроса) |
| POST | `/clear` | Очистка истории диалога |

## MCP-серверы

Приложение использует два внешних MCP-сервера, подключаемых по stdio. Их нужно установить заранее.

### macos-notification-mcp

Отправка нативных macOS-уведомлений. AI вызывает инструмент `banner_notification` после завершения задачи.

- **Репозиторий:** https://github.com/devizor/macos-notification-mcp
- **Установка:** устанавливается автоматически при первом вызове через `uvx` (требуется [uv](https://docs.astral.sh/uv/))

```bash
# Убедитесь, что uv установлен
brew install uv
```

### mcp-server-gist

Создание GitHub Gist через инструмент `create-gist`. Позволяет AI сохранять сгенерированный код в гисты.

- **Репозиторий:** https://github.com/coreycao/mcp-server-gist
- **Установка:**

```bash
# Клонировать и собрать
git clone https://github.com/coreycao/mcp-server-gist.git
cd mcp-server-gist
npm install
npm run build
```

- **Переменные окружения:**
  - `GITHUB_GIST_TOKEN` — [Personal Access Token](https://github.com/settings/tokens) с правами на создание гистов
  - `MCP_GIST_SERVER_PATH` — абсолютный путь до `build/index.js` собранного сервера

```bash
export MCP_GIST_SERVER_PATH=/path/to/mcp-server-gist/build/index.js
```

## Сервисы и дашборды

| Сервис | URL |
|---|---|
| Чат-интерфейс | http://localhost:8080 |
| Prometheus UI | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| Jaeger UI | http://localhost:16686 |
