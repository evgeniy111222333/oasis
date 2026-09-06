# Eclipse Roleplay — Клієнтська та Серверна кодова база (v26.1.2)

Цей репозиторій містить вихідний код кастомного клієнта Minecraft версії `26.1.2` (на базі Minecraft `1.21.2`) та серверного плагіна авторизації для ядра Purpur.

---

## 📂 Детальна структура репозиторію та опис кодової бази

### 1. Клієнтська частина (`client-mod-gradle/` і `client-mod/`)
* **`client-mod-gradle/`** — Основний проект Fabric-моду (збірка через Gradle), який використовується для розширення клієнта.
  * **`src/client/java/ua/rp/chat/client/mixin/`** — Важливі Mixin-класи для зміни поведінки гри:
    * `PlayerRendererMixin.java` — Динамічне приховування голови та шолома локального гравця для огляду від першої особи.
    * `ItemInHandRendererMixin.java` — Вимкнення стандартного відмальовування ванільних рук від першої особи.
    * `LevelRendererMixin.java` — Перехоплення етапів `extractLevel` та `renderLevel` для підготовки рендеру 3D-моделі гравця.
    * `CameraMixin.java` — Накладання фізичних зміщень та стабілізації на камеру гравця.
    * `EntityRenderDispatcherMixin.java` — Примусовий рендеринг тіла локального гравця у першій особі.
  * **`src/client/java/ua/rp/chat/client/camera/`** — Менеджер камери `SmartCameraManager.java` (фізика пружини-амортизатора, Neck-стабілізація по Y, зміщення при погляді вниз).
  * **`src/client/java/ua/rp/chat/client/render/`** — Інтерфейс `LocalPlayerRenderState.java` для маркування стану рендеру гравця.
* **`client-mod/`** — Копія клієнтського модуля без Gradle-налаштувань (використовується для порівняння).

### 2. Серверна частина (`src/`)
* **`src/main/java/ua/rp/chat/`** — Bukkit/Purpur плагін `RPChat` (Java 25).
  * **`auth/AuthWebServer.java`** — Вбудований авторизаційний веб-сервер (порт `25580`). Додано підтримку preflight-запитів `OPTIONS` для сумісності з CEF-браузером Chromium.
  * **`auth/AuthDatabase.java`** — Робота з локальною базу даних SQLite.
  * **`auth/AuthManager.java`** — Основна бізнес-логіка авторизації та сесій гравців.
* **`src/main/resources/web/`** — Статичні веб-файли інтерфейсу авторизації/реєстрації (HTML/CSS/JS), які відображаються у CEF-браузері клієнта.

### 3. Лаунчер (`launcher/`)
* **`launcher/main.js`** — Головний процес Electron-лаунчера. Тут прописані вимоги до SHA-1 хешів та розмірів клієнтських модів для автооновлення.

---

## 🛠️ Компіляція та збірка (Compilation & Build)

### Збірка клієнтського модуля (Fabric mod):
1. Перейдіть до папки `client-mod-gradle/`.
2. Виконайте команду:
   ```bash
   .\gradlew.bat build
   ```
3. Отриманий JAR-файл буде знаходитися за шляхом: `client-mod-gradle/build/libs/eclipseclient-1.0.0.jar`.

### Збірка серверного плагіна (Purpur plugin):
Для компіляції використовується Java 25. Залежності підключаються з локальної папки `libraries/` та файл `versions/26.1.2/purpur-26.1.2.jar`.
Для запуску сервера використовуйте файл `.\run.bat` у корені.

---

## Доступ до production-хоста

Локальна копія SSH-ключа зберігається у `D:\23\secrets\eclipse-stock.pem`.
Каталог `secrets/` виключений із Git; приватний ключ заборонено додавати до комітів,
публікацій, логів або передавати третім особам.

- Хост: `13.51.232.191`
- Користувач: `ubuntu`
- Корінь сервера: `/opt/eclipse-rp`
- Systemd-сервіс: `eclipse-rp`
- Локальний ключ можна перевизначити змінною `ECLIPSE_VPS_SSH_KEY`.

Підключення з PowerShell:

```powershell
ssh -i D:\23\secrets\eclipse-stock.pem ubuntu@13.51.232.191
```

Останні серверні логи:

```bash
sudo journalctl -u eclipse-rp -n 300 --no-pager
```

Спостереження за логом у реальному часі:

```bash
sudo journalctl -u eclipse-rp -f
```

Штатний `deployment/publish_update.py` використовує цей ключ для атомарного
розгортання серверного мода, перевірки готовності та автоматичного rollback.
