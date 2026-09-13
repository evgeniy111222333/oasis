# Eclipse RolePlay — деплой, збірка та публікація релізів

Це практична інструкція: прочитав → виконав команду. Нічого аналізувати не потрібно.

Уся логіка публікації зібрана в **одному** скрипті — `deployment/publish_update.py`.
Опис оновлення передається **аргументами командного рядка**, окремий файл редагувати **не треба**
(скрипт сам записує `deployment/release.json`).

---

## 0. TL;DR — випустити оновлення однією командою

```powershell
cd D:\23
python deployment\publish_update.py `
  --title   "Карвер: миттєве зникнення виділеного" `
  --summary "Один рядок суті оновлення" `
  --note    "Перший пункт списку змін" `
  --note    "Другий пункт списку змін"
```

Опційні аргументи:

| Аргумент | Призначення | За замовчуванням |
|---|---|---|
| `--title` | Заголовок оновлення (обов'язковий) | — |
| `--summary` | Короткий опис (обов'язковий) | — |
| `--note` | Пункт списку змін; можна повторювати багато разів | порожньо |
| `--id` | Ідентифікатор релізу | час `YYYYMMDD-HHMMSS` |
| `--button-label` | Напис кнопки в лаунчері | `ЗАГРУЗИТЬ ОБНОВЛЕНИЕ` |
| `--success-message` | Повідомлення після встановлення | `Обновление загружено и установлено.` |

> Текст опису пиши російською — саме так його показує лаунчер.

---

## 1. Що робить `publish_update.py` (крок за кроком)

1. Запускає `fabric-server\build.ps1` — Gradle `clean`/`check`/`build` для `:server` і `:client`
   (компіляція + всі перевірки/тести обох сторін).
2. Записує `deployment/release.json` з переданих `--title/--summary/--note`.
3. Запускає `build_distribution.py` → збирає `distribution-build/`.
4. `deploy_vps_server_mod.py` — атомарний деплой серверного мода на VPS (*best-effort*).
5. Google Drive: заливає реліз, перебудовує маніфест, оновлює лише маніфест (*best-effort*).
6. `upload_r2_distribution.py` — заливає дистрибутив на Cloudflare R2 (**обов'язковий**).
7. `upload_vps_distribution.py` — копіює дистрибутив на VPS-файлосервер (*best-effort*).
8. `upload_github_distribution.py` — публікує дистрибутив у гілку `dist` на GitHub (**обов'язковий**).

Якщо кроки 6 або 8 падають — публікація зупиняється з помилкою. Кроки 4, 5, 7 лише
попереджають і не блокують решту.

---

## 2. Передумови (один раз налаштувати)

1. **Java 25 (JDK)** — має бути в `JAVA_HOME` або в `PATH` (перевірка: `java -version`).
2. **Python 3.10+**.
3. **Python-залежності:**
   ```powershell
   pip install -r deployment\requirements-r2.txt
   pip install -r deployment\requirements-google-drive.txt
   ```
4. **Секрети** (каталог `secrets/` виключений із git, у репозиторій не додавати):
   - `secrets/eclipse-stock.pem` — SSH-ключ до VPS.
   - `secrets/google-drive-oauth.json` — OAuth client для Drive.
   - `secrets/google-drive-token.json` — збережений токен Drive (оновлюється скриптом).
5. **Змінні середовища** (необов'язкові, мають розумні значення за замовчуванням):

   | Змінна | Призначення | За замовчуванням |
   |---|---|---|
   | `ECLIPSE_VPS_SSH_KEY` | Шлях до SSH-ключа VPS | `secrets/eclipse-stock.pem` |
   | `ECLIPSE_VPS_HOST` | Хост VPS | `13.51.232.191` |
   | `ECLIPSE_VPS_USER` | Користувач VPS | `ubuntu` |
   | `ECLIPSE_DISTRIBUTION_R2_ACCESS_KEY_ID` | R2 (дистрибутив) | — (обов'язкова для R2) |
   | `ECLIPSE_DISTRIBUTION_R2_SECRET_ACCESS_KEY` | R2 (дистрибутив) | — (обов'язкова для R2) |
   | `ECLIPSE_API_URL` | API для лаунчера в dev | `https://api.eclipse-roleplay.online` |
   | `ECLIPSE_DISTRIBUTION_BASE_URL` | База дистрибутива для лаунчера | `https://api.eclipse-roleplay.online/dist` |

---

## 3. Окремо кожен скрипт — коли запускати

Усі команди — з кореня репозиторію `D:\23`.

| Скрипт | Що робить | Коли запускати |
|---|---|---|
| `deployment\publish_update.py` | Повний цикл релізу (кроки 1–8) | Звичайний випуск оновлення |
| `fabric-server\build.ps1` | Збірка + перевірки `:server` і `:client`, синк дистрибутива | Хочеться лише зібрати/перевірити |
| `deployment\build_distribution.py` | Збирає `distribution-build/` з `plugins/RPChat/client` + інсталятор лаунчера | Після збірки клієнта |
| `deployment\deploy_vps_server_mod.py` | Атомарний деплой серверного мода на VPS з ролбеком | Оновився серверний код |
| `deployment\upload_r2_distribution.py` | Заливає `distribution-build/` на Cloudflare R2 | Потрібна публікація на R2 |
| `deployment\upload_google_drive_distribution.py` | Заливає реліз на Google Drive; `--authorize` — перша авторизація; `--manifest-only` — оновити лише маніфест | Потрібне Drive-дзеркало |
| `deployment\upload_vps_distribution.py` | Копіює дистрибутив на VPS і атомарно підмінює клієнтські каталоги | Потрібне VPS-дзеркало |
| `deployment\upload_github_distribution.py` | Публікує `distribution-build/` у гілку `dist` на GitHub | Потрібне GitHub-дзеркало |
| `deployment\sync_client_manifest_atomic.sh` | На VPS: атомарно підмінює `plugins/RPChat/client` і `config/RPChat/client`, перевіряючи SHA-1 кожного мода | Викликається `upload_vps_distribution.py`, вручну — рідко |
| `deployment\install_world_atomic.sh` | На VPS: атомарна заміна світу з бекапом і ролбеком | Імпорт/заміна світу |
| `deployment\setup-lightsail.sh` | Початкове налаштування VPS | Новий сервер |
| `deployment\migrate_to_fabric.sh` | Міграція з Purpur/Paper на Fabric | Раз на сервер |
| `deployment\test_build_distribution.py` | Юніт-тести `build_distribution.py` | Перед релізом |
| `deployment\test_deploy_vps_server_mod.py` | Юніт-тести деплою серверного мода | Перед релізом |

Інфраструктурні файли: `deployment\Caddyfile` (реверс-проксі), `deployment\eclipse-rp-fabric.service`
(systemd), `deployment\eclipse-rp.service` (legacy).

---

## 4. Лише зібрати локально (без публікації)

```powershell
# Повна збірка + перевірки обох сторін
powershell -NoProfile -ExecutionPolicy Bypass -File D:\23\fabric-server\build.ps1
```

Швидший варіант — тільки клієнт (наприклад, після правок у `client-mod-gradle`):

```powershell
cd D:\23\fabric-server
.\gradlew.bat :client:clientClasses :client:testClasses   # швидка компіляція + тести
.\gradlew.bat :client:build                               # jar + синк у клієнтські каталоги
```

Окремі перевірки Carver/мікровокселів:

```powershell
cd D:\23\fabric-server
.\gradlew.bat :client:verifyCarverHologramFilter :client:verifyMicrovoxelCore :client:verifyCarverMirror
```

---

## 5. Де які версії та де опиняються артефакти

**Версії (джерела істини):**
- Серверний мод: `fabric-server\gradle.properties` → `mod_version` (напр. `1.5.17`).
- Клієнтський мод: `fabric-server\client-mod-gradle\gradle.properties` → `mod_version` (напр. `1.5.24`).
- Лаунчер: `launcher\package.json` → `version` (напр. `1.0.22`).

**Куди пише збірка (`syncClientDistribution` / `syncServerDistribution`):**
- Серверний jar → `fabric-server\mods\eclipseserver-<serverVer>.jar`.
- Клієнтський jar `eclipse-client-<clientVer>.jar` + `mods.json` → **три** каталоги:
  1. `plugins\RPChat\client\` — те, що читає лаунчер у dev і `build_distribution.py`;
  2. `fabric-server\plugins\RPChat\client\`;
  3. `fabric-server\config\RPChat\client\`.
  Старі `eclipse-client-*` видаляються, SHA-1 і розмір у `mods.json` перераховуються.

**Публічний дистрибутив:** `distribution-build\` (гілка/дзеркала), у ньому:
`client\` (моди + профіль), `launcher\stable\` (інсталятор `.exe`), `manifests\production.json`
(єдиний маніфест, який читає лаунчер), `launcher\latest.json`.

**Що виключає `.gitignore` (не комітити):** `distribution-build/`, `secrets/`,
`deployment/google_drive.local.json`, `deployment/google_drive_release.json`.

---

## 6. Як лаунчер отримує оновлення

1. Лаунчер тягне маніфест `production.json` із `https://api.eclipse-roleplay.online/dist`
   (або з `ECLIPSE_DISTRIBUTION_BASE_URL` у dev).
2. Звіряє `sha1`/`sha256`/`size` кожного керованого мода (префікс `eclipse-client-`).
3. Завантажує з дзеркал (R2 / VPS / Drive / GitHub) і атомарно підмінює моди.
4. У dev-режимі (`npm start` у `launcher/`) як джерело використовується локальний
   `plugins/RPChat/client/mods.json`.

---

## 7. Типові проблеми та швидкі рішення

| Симптом | Причина | Що робити |
|---|---|---|
| `invalid_grant: Bad Request` (Google Drive) | OAuth-токен прострочений/відкликаний | `python deployment\upload_google_drive_distribution.py --authorize` (відкриє браузер) |
| `ssh: connect ... port 22: Connection timed out` | VPS вимкнено / firewall / невірний хост | Перевір живлення й Security Group; задай `ECLIPSE_VPS_HOST` |
| Публікація впала на R2 | Не задані R2-ключі | Задай `ECLIPSE_DISTRIBUTION_R2_ACCESS_KEY_ID` і `ECLIPSE_DISTRIBUTION_R2_SECRET_ACCESS_KEY` |
| Git push впав | Немає облікових даних для `github.com/evgeniy111222333/oasis.git` | Налаштуй git-креденшели/токен |
| Dev-збірка: `incompatible types` | Помилка компіляції у зміненому файлі | Читай рядок з `:.java:NNN` у виводі Gradle |
| Помилка `Missing distribution input(s)` | Немає інсталятора лаунчера потрібної версії | Збери `launcher` (`npm run dist`) або підніми `launcher/package.json` |
| Серверний мод не оновлюється на VPS | Checksum збігається → деплой пропускається | Це нормально: `SERVER_MOD_ALREADY_CURRENT` |

---

## 8. Важливі нюанси

- **Опис релізу — тільки через CLI** (`--title/--summary/--note`). Ручний запис у
  `release.json` зайвий: `publish_update.py` перезапише файл сам.
- **Серверний і клієнтський мод мають незалежні версії** (різні `gradle.properties`).
  Якщо змінюєш мережевий протокол між ними — піднімай обидві версії узгоджено й тримай
  паритет mirror-файлів (перевірка `:server:verifyCarverParity`).
- **Атомарність деплою:** і серверний мод, і клієнтський каталог на VPS, і світ
  підмінюються через staging + backup + rollback; перевіряється відкриття портів `25565`
  та `25580`, після чого старий мод/світ лишається в `backups/`.
- **`deploy_vps_server_mod.py`** читає версію з `fabric-server/gradle.properties`, але на
  проді «живий» файл має фіксовану назву `mods/eclipseserver-1.4.5.jar` (константа в скрипті).
  Це очікувано: на VPS перевіряється саме цей шлях.
- **Best-effort vs обов'язкове:** R2 і GitHub зупиняють публікацію при збої; Drive/VPS-деплой —
  ні (лише попередження).

---

## 9. Швидка довідка команд

```powershell
# Повний реліз з описом
python deployment\publish_update.py --title "…" --summary "…" --note "…" --note "…"

# Тільки збірка + перевірки
powershell -NoProfile -ExecutionPolicy Bypass -File fabric-server\build.ps1

# Перезібрати дистрибутив і залити на R2
python deployment\build_distribution.py
python deployment\upload_r2_distribution.py

# Авторизувати Google Drive
python deployment\upload_google_drive_distribution.py --authorize

# Деплой серверного мода на VPS
python deployment\deploy_vps_server_mod.py
```
