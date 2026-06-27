// 🌾 Oasis RP Server — Frontend Logic (app.js)

// Global state
let authToken = '';
let isRegisteredUser = false;
let mcUsername = '';

// Get URL query parameters
document.addEventListener('DOMContentLoaded', () => {
    const urlParams = new URLSearchParams(window.location.search);
    authToken = urlParams.get('token');

    if (!authToken) {
        showGlobalError('Відсутній токен авторизації. Перезайдіть у гру.');
        return;
    }

    // Fetch initial player status from server
    fetchStatus();
});

// Fetch registration status and details from server API
async function fetchStatus() {
    try {
        const response = await fetch(`/api/status?token=${encodeURIComponent(authToken)}`);
        const data = await response.json();

        if (!data.success) {
            showGlobalError(data.message || 'Недійсний токен авторизації.');
            return;
        }

        mcUsername = data.username;
        isRegisteredUser = data.registered;

        if (isRegisteredUser) {
            // User is registered, show login tab, pre-fill login field if returned
            switchTab('login');
            if (data.loginName) {
                document.getElementById('loginName').value = data.loginName;
                document.getElementById('loginName').readOnly = true; // prevent changing registered login
            }
        } else {
            // User is new, show registration tab
            switchTab('register');
        }
    } catch (err) {
        console.error(err);
        showGlobalError('Помилка зв\'язку з сервером авторизації.');
    }
}

// Global error banner (if token invalid)
function showGlobalError(msg) {
    const errorBox = document.getElementById('loginError');
    errorBox.innerText = msg;
    errorBox.style.display = 'block';
    
    // Disable all submit buttons
    document.querySelectorAll('.submit-btn').forEach(btn => {
        btn.disabled = true;
        btn.style.opacity = '0.5';
        btn.style.cursor = 'not-allowed';
    });
}

// Switch tabs: login, register, recovery
function switchTab(tabName) {
    // If not validated and trying to navigate when token is invalid
    if (!authToken && tabName !== 'login') return;

    // Reset alert boxes
    document.querySelectorAll('.alert-box').forEach(box => {
        box.style.display = 'none';
        box.innerText = '';
    });

    // Remove active class from buttons
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.remove('active');
    });

    // Hide all forms
    document.querySelectorAll('.auth-form').forEach(form => {
        form.classList.remove('active');
    });

    // Activate the selected tab
    const tabsNav = document.getElementById('tabsNav');
    if (tabName === 'login') {
        document.getElementById('loginForm').classList.add('active');
        tabsNav.querySelector('button:nth-child(1)').classList.add('active');
        tabsNav.style.display = 'flex';
    } else if (tabName === 'register') {
        document.getElementById('registerForm').classList.add('active');
        tabsNav.querySelector('button:nth-child(2)').classList.add('active');
        tabsNav.style.display = 'flex';
    } else if (tabName === 'recovery') {
        document.getElementById('recoveryForm').classList.add('active');
        tabsNav.style.display = 'none'; // hide navigation for recovery
    }
}

// Password visibility toggler
function togglePasswordVisibility(inputId, btn) {
    const input = document.getElementById(inputId);
    const icon = btn.querySelector('i');
    
    if (input.type === 'password') {
        input.type = 'text';
        icon.classList.remove('fa-eye');
        icon.classList.add('fa-eye-slash');
    } else {
        input.type = 'password';
        icon.classList.remove('fa-eye-slash');
        icon.classList.add('fa-eye');
    }
}

// Handle login submission
async function handleLoginSubmit(event) {
    event.preventDefault();
    const loginError = document.getElementById('loginError');
    loginError.style.display = 'none';

    const loginName = document.getElementById('loginName').value.trim();
    const password = document.getElementById('loginPassword').value;
    const rememberMe = document.getElementById('rememberMe').checked;

    if (!loginName || !password) {
        showError(loginError, 'Будь ласка, заповніть всі поля.');
        return;
    }

    setButtonLoading('loginBtn', true);

    try {
        const response = await fetch('/api/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                token: authToken,
                loginName: loginName,
                password: password,
                rememberMe: rememberMe
            })
        });

        const result = await response.json();

        if (result.success) {
            showSuccessScreen('Успішна авторизація!', `Вітаємо з поверненням, ${result.rpName || loginName}!`);
        } else {
            showError(loginError, result.message || 'Невірний пароль.');
        }
    } catch (err) {
        showError(loginError, 'Помилка мережі при авторизації.');
    } finally {
        setButtonLoading('loginBtn', false);
    }
}

// Handle register submission
async function handleRegisterSubmit(event) {
    event.preventDefault();
    const registerError = document.getElementById('registerError');
    registerError.style.display = 'none';

    const loginName = document.getElementById('regLogin').value.trim();
    const rpName = document.getElementById('regRpName').value.trim();
    const email = document.getElementById('regEmail').value.trim();
    const password = document.getElementById('regPassword').value;
    const confirmPassword = document.getElementById('regConfirmPassword').value;
    const agreeTerms = document.getElementById('agreeTerms').checked;

    // Validation checks
    if (!loginName || !rpName || !email || !password || !confirmPassword) {
        showError(registerError, 'Будь ласка, заповніть всі обов\'язкові поля.');
        return;
    }

    // Account login validation (alphanumeric, 4-16 chars)
    const loginRegex = /^[a-zA-Z0-9_]{4,16}$/;
    if (!loginRegex.test(loginName)) {
        showError(registerError, 'Логін повинен містити тільки англійські літери, цифри та символ підкреслення (4-16 символів).');
        return;
    }

    // RolePlay Name validation: FirstName LastName (e.g. "Іван Петренко" or "Иван Петренко")
    // Allows Ukrainian/Russian Cyrillic and English characters, must start with capital letter
    const rpNameRegex = /^[A-ZА-ЯІЄЇ][a-zа-яієї']+\s+[A-ZА-ЯІЄЇ][a-zа-яієї']+$/;
    if (!rpNameRegex.test(rpName)) {
        showError(registerError, 'Ім\'я та Прізвище мають бути у форматі "Іван Петренко" (два слова з великої літери).');
        return;
    }

    if (password.length < 6) {
        showError(registerError, 'Пароль повинен містити не менше 6 символів.');
        return;
    }

    if (password !== confirmPassword) {
        showError(registerError, 'Паролі не збігаються.');
        return;
    }

    if (!agreeTerms) {
        showError(registerError, 'Ви повинні погодитися з правилами сервера.');
        return;
    }

    setButtonLoading('registerBtn', true);

    try {
        const response = await fetch('/api/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                token: authToken,
                loginName: loginName,
                rpName: rpName,
                email: email,
                password: password
            })
        });

        const result = await response.json();

        if (result.success) {
            showSuccessScreen('Акаунт створено!', `Вітаємо на сервері, ${rpName}! Ваш нікнейм змінено.`);
        } else {
            showError(registerError, result.message || 'Помилка реєстрації. Логін вже зайнятий.');
        }
    } catch (err) {
        showError(registerError, 'Помилка мережі при реєстрації.');
    } finally {
        setButtonLoading('registerBtn', false);
    }
}

// Handle password recovery submission
async function handleRecoverySubmit(event) {
    event.preventDefault();
    const recoveryError = document.getElementById('recoveryError');
    const recoverySuccess = document.getElementById('recoverySuccess');
    recoveryError.style.display = 'none';
    recoverySuccess.style.display = 'none';

    const email = document.getElementById('recoveryEmail').value.trim();

    if (!email) {
        showError(recoveryError, 'Будь ласка, введіть email.');
        return;
    }

    setButtonLoading('recoveryBtn', true);

    try {
        const response = await fetch('/api/recovery', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                token: authToken,
                email: email
            })
        });

        const result = await response.json();

        if (result.success) {
            recoverySuccess.innerText = result.message || 'Код відновлення надіслано на вашу пошту!';
            recoverySuccess.style.display = 'block';
            document.getElementById('recoveryEmail').value = '';
        } else {
            showError(recoveryError, result.message || 'Цей email не знайдено в базі даних.');
        }
    } catch (err) {
        showError(recoveryError, 'Помилка мережі при відновленні.');
    } finally {
        setButtonLoading('recoveryBtn', false);
    }
}

// Helper: Show error alert
function showError(element, msg) {
    element.innerText = msg;
    element.style.display = 'block';
}

// Helper: Set submit button loading state
function setButtonLoading(btnId, isLoading) {
    const btn = document.getElementById(btnId);
    if (isLoading) {
        btn.classList.add('loading');
        btn.disabled = true;
    } else {
        btn.classList.remove('loading');
        btn.disabled = false;
    }
}

// Helper: Show full-screen success screen
function showSuccessScreen(title, message) {
    const successScreen = document.getElementById('successScreen');
    successScreen.querySelector('h2').innerText = title;
    document.getElementById('successMessage').innerText = message;
    successScreen.classList.add('active');
}
