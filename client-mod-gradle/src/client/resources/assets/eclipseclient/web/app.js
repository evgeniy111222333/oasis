let authToken = "";
let isRegisteredUser = false;
let mcUsername = "";
let mcUuid = "";
let appearanceData = "";
let sessionRecoveryInFlight = null;
let apiUrl = "https://api.eclipse-roleplay.online";
const savedCredentialsKey = "eclipseAuth.savedCredentials.v1";

// Safe diagnostics consumed by McefDiagnostics in latest.log. Do not log
// tokens, passwords, email addresses, or JSON request bodies here.
function authDiagnostic(stage, detail = "") {
    console.info(`[ECLIPSE-AUTH] ${stage}${detail ? ` ${detail}` : ""}`);
}

function safeApiEndpoint(path) {
    try {
        return new URL(path, apiUrl).origin + path.split("?")[0];
    } catch (_) {
        return "<invalid-api-url>";
    }
}

window.addEventListener("error", (event) => {
    authDiagnostic("window-error", `${event.message || "unknown"} at ${event.filename || "<inline>"}:${event.lineno || 0}`);
});

window.addEventListener("unhandledrejection", (event) => {
    const reason = event.reason;
    authDiagnostic("unhandled-rejection", reason instanceof Error ? `${reason.name}: ${reason.message}` : String(reason || "unknown"));
});

document.addEventListener("DOMContentLoaded", () => {
    const params = new URLSearchParams(window.location.search);
    authToken = params.get("token") || "";
    mcUsername = params.get("username") || "";
    apiUrl = params.get("apiUrl") || apiUrl;
    authDiagnostic("bootstrap", `origin=${location.origin || "file"} api=${safeApiEndpoint("/")}`);

    document.querySelectorAll("[data-tab]").forEach((button) => {
        button.addEventListener("click", () => switchTab(button.dataset.tab));
    });

    document.querySelectorAll("[data-toggle]").forEach((button) => {
        button.addEventListener("click", () => togglePassword(button.dataset.toggle, button));
    });

    document.getElementById("loginForm").addEventListener("submit", handleLoginSubmit);
    document.getElementById("registerForm").addEventListener("submit", handleRegisterSubmit);
    document.getElementById("recoveryForm").addEventListener("submit", handleRecoverySubmit);
    document.getElementById("appearanceFile").addEventListener("change", handleAppearanceSelect);
    restoreSavedCredentials();

    if (!authToken) {
        recoverAuthSession("Восстанавливаем сессию авторизации...").then((recovered) => {
            if (!recovered) {
                showGlobalError("Сессия авторизации не найдена. Перезайдите на сервер.");
            }
        });
        return;
    }

    fetchStatus();
});

async function fetchStatus() {
    try {
        const endpoint = `${apiUrl}/api/status?token=${encodeURIComponent(authToken)}`;
        authDiagnostic("status-request", `url=${safeApiEndpoint("/api/status")}`);
        const response = await fetch(endpoint);
        authDiagnostic("status-response", `http=${response.status}`);
        const data = await response.json();

        if (!data.success) {
            if (await recoverAuthSession(data.message || "Сессия авторизации обновляется...")) {
                return;
            }
            showGlobalError(data.message || "Сессия авторизации уже недействительна. Перезайдите на сервер.");
            return;
        }

        mcUuid = data.uuid || "";
        mcUsername = data.username || "Steve";
        isRegisteredUser = Boolean(data.registered);
        hydratePlayerCard(data.appearanceUrl);

        if (isRegisteredUser) {
            switchTab("login");
            if (data.loginName) {
                const loginInput = document.getElementById("loginName");
                loginInput.value = data.loginName;
                loginInput.readOnly = true;
                restoreSavedCredentials(data.loginName);
            }
        } else {
            switchTab("register");
        }
    } catch (error) {
        authDiagnostic("status-error", `${error?.name || "Error"}: ${error?.message || String(error)}`);
        if (await recoverAuthSession("Проверяем активную сессию...")) {
            return;
        }
        showGlobalError("Нет связи с сервером авторизации.");
    }
}

async function recoverAuthSession(message) {
    if (sessionRecoveryInFlight) {
        return sessionRecoveryInFlight;
    }
    sessionRecoveryInFlight = (async () => {
        const username = mcUsername || new URLSearchParams(window.location.search).get("username") || "";
        if (!username) {
            return false;
        }

        switchTab("login");
        showError(document.getElementById("loginError"), message);
        for (let attempt = 0; attempt < 16; attempt++) {
            try {
                const endpoint = `${apiUrl}/api/client-session?username=${encodeURIComponent(username)}&ts=${Date.now()}`;
                authDiagnostic("recovery-request", `attempt=${attempt + 1} url=${safeApiEndpoint("/api/client-session")}`);
                const response = await fetch(endpoint, {
                    cache: "no-store"
                });
                authDiagnostic("recovery-response", `attempt=${attempt + 1} http=${response.status}`);
                if (response.status === 200) {
                    const data = await response.json();
                    if (data.success && data.authUrl) {
                        const newUrl = new URL(data.authUrl);
                        const newToken = newUrl.searchParams.get("token") || "";
                        const newUsername = newUrl.searchParams.get("username") || "";
                        const currentUrl = new URL(window.location.href);
                        currentUrl.searchParams.set("token", newToken);
                        currentUrl.searchParams.set("username", newUsername);
                        currentUrl.searchParams.set("recover", Date.now().toString());
                        window.location.replace(currentUrl.toString());
                        return true;
                    }
                }
            } catch (error) {
                authDiagnostic("recovery-error", `attempt=${attempt + 1} ${error?.name || "Error"}: ${error?.message || String(error)}`);
                // The server or token may still be starting; retry inside the bounded window.
            }
            await new Promise((resolve) => window.setTimeout(resolve, attempt < 5 ? 350 : 650));
        }
        return false;
    })();

    try {
        return await sessionRecoveryInFlight;
    } finally {
        sessionRecoveryInFlight = null;
    }
}

function hydratePlayerCard(appearanceUrl = "") {
    document.getElementById("playerName").textContent = mcUsername;
    document.getElementById("playerMode").textContent = isRegisteredUser ? "Облик найден" : "Новый персонаж";
    document.getElementById("playerSeal").textContent = getInitials(mcUsername);
    setSkinPreview(appearanceUrl || `https://minotar.net/skin/${encodeURIComponent(mcUsername)}`);
}

function setSkinPreview(src) {
    const skinModel = document.getElementById("skinModel");
    if (!skinModel || !src) {
        return;
    }

    skinModel.classList.add("skin-model-loading");
    const image = new Image();
    image.decoding = "async";
    image.fetchPriority = "high";
    image.onload = () => {
        skinModel.style.setProperty("--skin-url", toCssUrl(src));
        skinModel.classList.remove("skin-model-empty");
        skinModel.classList.remove("skin-model-loading");
    };
    image.onerror = () => {
        if (src.includes("/Steve")) {
            skinModel.classList.remove("skin-model-loading");
            skinModel.classList.add("skin-model-empty");
            return;
        }
        setSkinPreview("https://minotar.net/skin/Steve");
    };
    image.src = src;
}

function toCssUrl(src) {
    return `url("${String(src).replace(/\\/g, "\\\\").replace(/"/g, '\\"')}")`;
}

function getInitials(username) {
    return (username || "ID").slice(0, 2).toUpperCase();
}

function switchTab(tabName) {
    clearAlerts();
    document.querySelectorAll(".auth-form").forEach((form) => form.classList.remove("active"));
    document.querySelectorAll(".tab-btn").forEach((button) => button.classList.remove("active"));

    const tabs = document.getElementById("tabsNav");
    tabs.style.display = tabName === "recovery" ? "none" : "grid";

    if (tabName === "register") {
        document.getElementById("registerForm").classList.add("active");
        document.querySelector('[data-tab="register"]').classList.add("active");
        setModeCopy("Новый облик", "Задайте имя, доступ и внешний образ персонажа.", "Регистрация");
        return;
    }

    if (tabName === "recovery") {
        document.getElementById("recoveryForm").classList.add("active");
        setModeCopy("Возврат доступа", "Восстановление через привязанный email.", "Восстановление");
        return;
    }

    document.getElementById("loginForm").classList.add("active");
    document.querySelector('[data-tab="login"]').classList.add("active");
    setModeCopy("Вход в мир", "Персонаж ожидает подтверждения владельца.", "Вход");
}

function setModeCopy(sceneTitle, sceneLead, panelTitle) {
    document.getElementById("sceneTitle").textContent = sceneTitle;
    document.getElementById("sceneLead").textContent = sceneLead;
    document.getElementById("panelTitle").textContent = panelTitle;
}

function togglePassword(inputId, button) {
    const input = document.getElementById(inputId);
    const showing = input.type === "text";
    input.type = showing ? "password" : "text";
    button.textContent = showing ? "Показать" : "Скрыть";
}

function handleAppearanceSelect(event) {
    const file = event.target.files && event.target.files[0];
    const errorBox = document.getElementById("registerError");
    clearAlerts();
    appearanceData = "";

    if (!file) {
        document.getElementById("appearanceFileLabel").textContent = "Загрузить образ";
        return;
    }

    if (file.type !== "image/png") {
        showError(errorBox, "Облик должен быть PNG-файлом.");
        event.target.value = "";
        return;
    }

    if (file.size > 512 * 1024) {
        showError(errorBox, "Файл образа слишком большой. Максимум 512 KB.");
        event.target.value = "";
        return;
    }

    const reader = new FileReader();
    reader.onload = () => {
        const img = new Image();
        img.onload = () => {
            if (img.width !== 64 || (img.height !== 64 && img.height !== 32)) {
                showError(errorBox, "Размер образа должен быть 64x64 или 64x32.");
                event.target.value = "";
                return;
            }
            appearanceData = reader.result;
            document.getElementById("appearanceFileLabel").textContent = file.name;
            setSkinPreview(reader.result);
            document.getElementById("playerMode").textContent = "Облик выбран";
        };
        img.onerror = () => showError(errorBox, "Не удалось прочитать PNG-файл.");
        img.src = reader.result;
    };
    reader.onerror = () => showError(errorBox, "Не удалось прочитать файл образа.");
    reader.readAsDataURL(file);
}

async function handleLoginSubmit(event) {
    event.preventDefault();
    const errorBox = document.getElementById("loginError");
    const loginName = document.getElementById("loginName").value.trim();
    const password = document.getElementById("loginPassword").value;

    if (!loginName || !password) {
        showError(errorBox, "Заполните логин и пароль.");
        return;
    }

    await submitAuth("loginBtn", "/api/login", {
        token: authToken,
        loginName,
        password,
        rememberMe: document.getElementById("rememberMe").checked
    }, errorBox, (result) => {
        persistSavedCredentials(loginName, password);
        showSuccess("Вход подтвержден", `С возвращением, ${result.rpName || loginName}.`);
    });
}

function restoreSavedCredentials(expectedLogin = "") {
    try {
        const saved = readSavedCredentials();
        const rememberInput = document.getElementById("rememberMe");
        const loginInput = document.getElementById("loginName");
        const passwordInput = document.getElementById("loginPassword");
        if (!rememberInput || !loginInput || !passwordInput || !saved.remember) {
            return false;
        }
        if (saved.loginName && (!expectedLogin || saved.loginName.toLowerCase() === expectedLogin.toLowerCase())) {
            if (!loginInput.value) {
                loginInput.value = saved.loginName;
            }
            passwordInput.value = saved.password || "";
            rememberInput.checked = true;
            return Boolean(saved.password);
        }
        if (expectedLogin) {
            passwordInput.value = "";
            rememberInput.checked = false;
        }
    } catch (error) {
        localStorage.removeItem(savedCredentialsKey);
    }
    return false;
}

function readSavedCredentials() {
    const current = localStorage.getItem(savedCredentialsKey);
    if (current) {
        return JSON.parse(current);
    }

    const legacyKey = Object.keys(localStorage).find((key) =>
        key !== savedCredentialsKey && key.endsWith("Auth.savedCredentials.v1")
    );
    if (!legacyKey) {
        return {};
    }

    const legacy = localStorage.getItem(legacyKey);
    if (!legacy) {
        return {};
    }
    localStorage.setItem(savedCredentialsKey, legacy);
    localStorage.removeItem(legacyKey);
    return JSON.parse(legacy);
}

function persistSavedCredentials(loginName, password) {
    const rememberInput = document.getElementById("rememberMe");
    if (!rememberInput || !rememberInput.checked) {
        localStorage.removeItem(savedCredentialsKey);
        return;
    }
    localStorage.setItem(savedCredentialsKey, JSON.stringify({
        remember: true,
        loginName,
        password
    }));
}

async function handleRegisterSubmit(event) {
    event.preventDefault();
    const errorBox = document.getElementById("registerError");
    const loginName = document.getElementById("regLogin").value.trim();
    const rpName = document.getElementById("regRpName").value.trim();
    const email = document.getElementById("regEmail").value.trim();
    const password = document.getElementById("regPassword").value;
    const confirmPassword = document.getElementById("regConfirmPassword").value;
    const appearanceModel = document.querySelector('input[name="appearanceModel"]:checked')?.value || "classic";

    if (!loginName || !rpName || !email || !password || !confirmPassword) {
        showError(errorBox, "Заполните все поля регистрации.");
        return;
    }

    if (!appearanceData) {
        showError(errorBox, "Загрузите облик персонажа перед созданием профиля.");
        return;
    }

    if (!/^[a-zA-Z0-9_]{4,16}$/.test(loginName)) {
        showError(errorBox, "Логин: 4-16 символов, латиница, цифры или подчеркивание.");
        return;
    }

    if (!/^[A-ZА-ЯЁ][a-zа-яё']+\s+[A-ZА-ЯЁ][a-zа-яё']+$/.test(rpName)) {
        showError(errorBox, "Имя персонажа должно быть в формате: Иван Петров.");
        return;
    }

    if (password.length < 6) {
        showError(errorBox, "Пароль должен содержать минимум 6 символов.");
        return;
    }

    if (password !== confirmPassword) {
        showError(errorBox, "Пароли не совпадают.");
        return;
    }

    if (!document.getElementById("agreeTerms").checked) {
        showError(errorBox, "Нужно принять правила сервера.");
        return;
    }

    await submitAuth("registerBtn", "/api/register", {
        token: authToken,
        loginName,
        rpName,
        email,
        password,
        appearanceModel,
        appearanceData
    }, errorBox, () => {
        showSuccess("Персонаж создан", `Добро пожаловать, ${rpName}.`);
    });
}

async function handleRecoverySubmit(event) {
    event.preventDefault();
    const errorBox = document.getElementById("recoveryError");
    const successBox = document.getElementById("recoverySuccess");
    const email = document.getElementById("recoveryEmail").value.trim();

    if (!email) {
        showError(errorBox, "Укажите email.");
        return;
    }

    await submitAuth("recoveryBtn", "/api/recovery", {
        token: authToken,
        email
    }, errorBox, (result) => {
        successBox.textContent = result.message || "Код восстановления отправлен.";
        successBox.style.display = "block";
    });
}

async function submitAuth(buttonId, url, payload, errorBox, onSuccess) {
    setLoading(buttonId, true);
    clearAlerts();

    try {
        authDiagnostic("submit-request", `operation=${url} api=${safeApiEndpoint(url)}`);
        const response = await fetch(`${apiUrl}${url}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        authDiagnostic("submit-response", `operation=${url} http=${response.status}`);
        const result = await response.json();

        if (!result.success) {
            showError(errorBox, result.message || "Запрос не выполнен.");
            return;
        }

        onSuccess(result);
    } catch (error) {
        authDiagnostic("submit-error", `operation=${url} ${error?.name || "Error"}: ${error?.message || String(error)}`);
        showError(errorBox, "Ошибка сети. Попробуйте еще раз.");
    } finally {
        setLoading(buttonId, false);
    }
}

function showGlobalError(message) {
    switchTab("login");
    showError(document.getElementById("loginError"), message);
    document.querySelectorAll("button, input").forEach((element) => {
        element.disabled = true;
    });
}

function showError(element, message) {
    element.textContent = message;
    element.style.display = "block";
}

function clearAlerts() {
    document.querySelectorAll(".alert").forEach((element) => {
        element.textContent = "";
        element.style.display = "none";
    });
}

function setLoading(buttonId, loading) {
    const button = document.getElementById(buttonId);
    button.disabled = loading;
    button.classList.toggle("loading", loading);
}

function showSuccess(title, message) {
    document.getElementById("successTitle").textContent = title;
    document.getElementById("successMessage").textContent = message;
    document.getElementById("successScreen").classList.add("active");
}
