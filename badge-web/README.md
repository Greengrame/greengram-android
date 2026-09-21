# LibreTG Badge Web Editor

Веб-редактор бейджей LibreTG. Работает как отдельный сервис на VPS и ходит в
Supabase под `service_role`, поэтому приложение остаётся read-only.

## Что умеет

- Вход по паролю (cookie-сессия, подписанная HMAC).
- Список профилей с поиском по ID, роли и тексту.
- Создание профиля и полное редактирование: роль, документы иконок, тексты,
  флаги `can_change_badge`, `show_badge_button`, `forcemorebutton`, `deleted`.
- Редактор блоков карточки (`badge_info_features`): заголовок, текст,
  document id, включён/выключен.

## Переменные окружения

Скопируй `.env.example` в `.env` и заполни:

| Переменная | Назначение |
| --- | --- |
| `PORT` | Порт приложения, по умолчанию `8080` |
| `ADMIN_PASSWORD` | Пароль для входа |
| `SESSION_SECRET` | Ключ подписи cookie-сессии |
| `SUPABASE_URL` | URL проекта Supabase |
| `SUPABASE_SERVICE_KEY` | Service-role ключ Supabase |

`SUPABASE_SERVICE_KEY` даёт полный доступ к базе. Не публикуй его и не передавай
в клиентские приложения.

## Деплой

```bash
docker compose up -d --build
```

Traefik подхватывает контейнер по меткам: хост `badge.tg.dexx.moe`,
entrypoint `websecure`, сертификат через резолвер `le`. Контейнер должен быть в
внешней сети `proxy`.

## Проверка

```bash
curl -sk https://badge.tg.dexx.moe/api/session
```

Должен вернуть `{"authenticated":false, ...}` без авторизации.
