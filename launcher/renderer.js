const { ipcRenderer } = require('electron');

const btnMinimize = document.getElementById('btnMinimize');
const btnClose = document.getElementById('btnClose');
const btnPlay = document.getElementById('btnPlay');
const usernameInput = document.getElementById('usernameInput');
const progressContainer = document.getElementById('progressContainer');
const progressBarFill = document.getElementById('progressBarFill');
const progressMessage = document.getElementById('progressMessage');
const progressPercent = document.getElementById('progressPercent');

// Settings modal elements
const btnSettings = document.getElementById('btnSettings');
const btnSettingsClose = document.getElementById('btnSettingsClose');
const settingsModal = document.getElementById('settingsModal');
const btnBrowseFolder = document.getElementById('btnBrowseFolder');
const gamePathInput = document.getElementById('gamePathInput');
const fullscreenInput = document.getElementById('fullscreenInput');
const optionalModsList = document.getElementById('optionalModsList');
const optionalModsCount = document.getElementById('optionalModsCount');
const optionalModsNotice = document.getElementById('optionalModsNotice');

let currentGamePath = '';
let usernameSaveTimer = null;

function setServerStatus({ online, text, players = '-- / --' }) {
    const statusDot = document.querySelector('.status-dot');
    const statusText = document.querySelector('.status-text');
    const onlineCount = document.querySelector('.online-count');
    if (!statusDot || !statusText || !onlineCount) {
        return;
    }
    statusDot.style.backgroundColor = online ? '#99C3A2' : '#E3A899';
    statusDot.style.boxShadow = online ? '0 0 8px #99C3A2' : '0 0 8px #E3A899';
    statusText.innerText = text;
    onlineCount.innerText = players;
}

setServerStatus({ online: false, text: 'Проверка сервера...' });

// Load config on startup
ipcRenderer.send('get-config');
ipcRenderer.on('config-data', (event, data) => {
    currentGamePath = data.gamePath;
    gamePathInput.value = currentGamePath;
    fullscreenInput.checked = data.fullscreen || false;
    if (Object.prototype.hasOwnProperty.call(data, 'lastUsername')) {
        usernameInput.value = data.lastUsername || '';
    }
    ipcRenderer.send('check-updates', { gamePath: currentGamePath });
    ipcRenderer.send('get-optional-mods');
    updateServerStatus();
});

// Settings toggle
btnSettings.addEventListener('click', () => {
    settingsModal.style.display = 'flex';
    ipcRenderer.send('get-optional-mods');
});

btnSettingsClose.addEventListener('click', () => {
    settingsModal.style.display = 'none';
});

// Browse folder
btnBrowseFolder.addEventListener('click', () => {
    ipcRenderer.send('select-directory');
});

ipcRenderer.on('selected-directory', (event, path) => {
    currentGamePath = path;
    gamePathInput.value = path;
    ipcRenderer.send('check-updates', { gamePath: currentGamePath });
});

// Save fullscreen configuration on change
fullscreenInput.addEventListener('change', () => {
    ipcRenderer.send('save-fullscreen', fullscreenInput.checked);
});

function renderOptionalMods(mods) {
    optionalModsList.replaceChildren();
    optionalModsCount.innerText = String(mods.length);

    if (mods.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'optional-mods-placeholder';
        const icon = document.createElement('i');
        icon.className = 'fa-solid fa-box-open';
        const text = document.createElement('span');
        text.innerText = 'Дополнительные моды пока не опубликованы.';
        empty.append(icon, text);
        optionalModsList.appendChild(empty);
        return;
    }

    for (const mod of mods) {
        const card = document.createElement('article');
        card.className = `optional-mod-card${mod.enabled ? ' enabled' : ''}`;

        const iconBox = document.createElement('div');
        iconBox.className = 'optional-mod-icon';
        const icon = document.createElement('i');
        icon.className = `fa-solid ${mod.icon || 'fa-cube'}`;
        iconBox.appendChild(icon);

        const copy = document.createElement('div');
        copy.className = 'optional-mod-copy';
        const titleRow = document.createElement('div');
        titleRow.className = 'optional-mod-title-row';
        const title = document.createElement('span');
        title.className = 'optional-mod-title';
        title.innerText = mod.name;
        titleRow.appendChild(title);
        if (mod.version) {
            const version = document.createElement('span');
            version.className = 'optional-mod-version';
            version.innerText = `v${mod.version}`;
            titleRow.appendChild(version);
        }
        if (mod.category) {
            const category = document.createElement('span');
            category.className = 'optional-mod-category';
            category.innerText = mod.category;
            titleRow.appendChild(category);
        }
        const description = document.createElement('p');
        description.className = 'optional-mod-description';
        description.innerText = mod.description || 'Дополнительный мод Eclipse RolePlay.';
        copy.append(titleRow, description);

        const control = document.createElement('div');
        control.className = 'optional-mod-control';
        const state = document.createElement('span');
        state.className = 'optional-mod-state';
        state.innerText = mod.enabled ? 'Включён' : 'Выключен';
        const switchLabel = document.createElement('label');
        switchLabel.className = 'switch mod-switch';
        switchLabel.title = `${mod.enabled ? 'Выключить' : 'Включить'} ${mod.name}`;
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = mod.enabled === true;
        checkbox.setAttribute('aria-label', `${mod.name}: ${mod.enabled ? 'включён' : 'выключен'}`);
        const slider = document.createElement('span');
        slider.className = 'slider';
        checkbox.addEventListener('change', () => {
            checkbox.disabled = true;
            state.innerText = 'Сохранение...';
            optionalModsNotice.classList.remove('error');
            optionalModsNotice.innerText = 'Настройка применится при следующем запуске игры.';
            ipcRenderer.send('set-optional-mod-enabled', {
                preferenceKey: mod.preferenceKey,
                enabled: checkbox.checked
            });
        });
        switchLabel.append(checkbox, slider);
        control.append(state, switchLabel);

        card.append(iconBox, copy, control);
        optionalModsList.appendChild(card);
    }
}

ipcRenderer.on('optional-mods-data', (event, data) => {
    if (data.success) {
        renderOptionalMods(Array.isArray(data.mods) ? data.mods : []);
        optionalModsNotice.classList.remove('error');
        optionalModsNotice.innerText = data.changed
            ? 'Сохранено. Изменение применится при следующем запуске игры.'
            : 'Включённые моды загружаются Fabric при запуске; выключенные JAR остаются на диске.';
    } else {
        optionalModsNotice.classList.add('error');
        optionalModsNotice.innerText = `Не удалось загрузить каталог: ${data.error || 'неизвестная ошибка'}`;
    }
});

usernameInput.addEventListener('input', () => {
    clearTimeout(usernameSaveTimer);
    usernameSaveTimer = setTimeout(() => {
        ipcRenderer.send('save-username', usernameInput.value.trim());
    }, 250);
});

// Window controls
btnMinimize.addEventListener('click', () => {
    ipcRenderer.send('window-minimize');
});

btnClose.addEventListener('click', () => {
    ipcRenderer.send('window-close');
});

// Launch game click
btnPlay.addEventListener('click', () => {
    const username = usernameInput.value.trim();

    if (!username) {
        alert('Пожалуйста, введите ваш никнейм!');
        return;
    }

    ipcRenderer.send('save-username', username);

    // Block play button and show progress bar
    btnPlay.disabled = true;
    progressContainer.style.display = 'flex';
    progressBarFill.style.width = '0%';
    progressPercent.innerText = '0%';
    progressMessage.innerText = 'Инициализация загрузчика...';

    // Send launch request with parameters to Electron main process
    ipcRenderer.send('launch-game', {
        username: username,
        gamePath: currentGamePath,
        fullscreen: fullscreenInput.checked
    });
});

// Handle launch progress updates from main process with visual throttling
let lastUiUpdate = 0;
const UI_THROTTLE_MS = 150; // Update progress text at most once every 150ms to prevent flickering

ipcRenderer.on('launch-status', (event, data) => {
    const now = Date.now();
    const isStateChange = data.status === 'success' || data.status === 'error' || data.progress === 100 || data.progress === 0;

    // Update UI only if throttled time passed, or if it is a major state change
    if (isStateChange || (now - lastUiUpdate > UI_THROTTLE_MS)) {
        progressBarFill.style.width = `${data.progress}%`;
        progressPercent.innerText = `${data.progress}%`;
        progressMessage.innerText = data.message;
        lastUiUpdate = now;
    }

    if (data.status === 'success') {
        // Game launched successfully
        btnPlay.disabled = true;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-gamepad"></i> В ИГРЕ</span>';
        btnPlay.classList.add('in-game-style');
        
        setTimeout(() => {
            progressContainer.style.display = 'none';
        }, 1500);
    } else if (data.status === 'error') {
        btnPlay.disabled = false;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> ИГРАТЬ</span>';
        btnPlay.classList.remove('in-game-style');
        progressContainer.style.display = 'flex';
    }
});

// Restore play button once the game window is closed
ipcRenderer.on('game-closed', () => {
    btnPlay.disabled = false;
    btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> ИГРАТЬ</span>';
    btnPlay.classList.remove('in-game-style');
});

// Dynamic server status & online query from plugin API.
function updateServerStatus() {
    ipcRenderer.send('get-server-status');
}

ipcRenderer.on('server-status-data', (event, data) => {
    if (data.success && data.status === 'online') {
        setServerStatus({
            online: true,
            text: 'Сервер работает',
            players: `${data.onlinePlayers} / ${data.maxPlayers}`
        });
    } else {
        setServerStatus({ online: false, text: 'Сервер выключен' });
    }
});

// Poll server status every 4 seconds
setInterval(updateServerStatus, 4000);
updateServerStatus(); // initial check

// Update modal UI elements
const updateModal = document.getElementById('updateModal');
const btnStartUpdate = document.getElementById('btnStartUpdate');
const btnGoogleDriveMirror = document.getElementById('btnGoogleDriveMirror');
const updateTitle = document.getElementById('updateTitle');
const updateSummary = document.getElementById('updateSummary');
const updateNotes = document.getElementById('updateNotes');
const updateNotesShell = updateNotes.closest('.update-notes-shell');
const updateToast = document.getElementById('updateToast');
const updateToastMessage = document.getElementById('updateToastMessage');
const modalProgressContainer = document.getElementById('modalProgressContainer');
const modalProgressBarFill = document.getElementById('modalProgressBarFill');
const modalProgressMessage = document.getElementById('modalProgressMessage');
const modalProgressPercent = document.getElementById('modalProgressPercent');

let isUpdating = false;
let currentRelease = null;

function syncUpdateNotesOverflow() {
    if (!updateNotesShell || updateNotes.style.display === 'none') return;
    const overflow = updateNotes.scrollHeight > updateNotes.clientHeight + 1;
    updateNotesShell.classList.toggle('can-scroll-up', overflow && updateNotes.scrollTop > 1);
    updateNotesShell.classList.toggle(
        'can-scroll-down',
        overflow && updateNotes.scrollTop + updateNotes.clientHeight < updateNotes.scrollHeight - 1
    );
}

updateNotes.addEventListener('scroll', syncUpdateNotesOverflow, { passive: true });
if (typeof ResizeObserver === 'function') {
    new ResizeObserver(syncUpdateNotesOverflow).observe(updateNotes);
}

function renderRelease(release) {
    currentRelease = release || null;
    updateTitle.innerText = release?.title || 'Доступно обновление';
    updateSummary.innerText = release?.summary || 'Подготовлено новое обновление клиента.';
    updateNotes.replaceChildren();
    updateNotes.scrollTop = 0;
    const notes = Array.isArray(release?.notes) ? release.notes.filter(note => typeof note === 'string' && note.trim()) : [];
    updateNotes.style.display = notes.length > 0 ? '' : 'none';
    for (const note of notes) {
        const row = document.createElement('div');
        row.className = 'file-item';
        const icon = document.createElement('i');
        icon.className = 'fa-solid fa-circle-check';
        const text = document.createElement('span');
        text.innerText = note;
        row.append(icon, text);
        updateNotes.appendChild(row);
    }
    requestAnimationFrame(syncUpdateNotesOverflow);
    const buttonIcon = document.createElement('i');
    buttonIcon.className = 'fa-solid fa-download';
    btnStartUpdate.replaceChildren(buttonIcon, document.createTextNode(` ${release?.buttonLabel || 'ЗАГРУЗИТЬ ОБНОВЛЕНИЕ'}`));
    btnGoogleDriveMirror.hidden = true;
}

function showGoogleDriveMirrorIfAvailable() {
    btnGoogleDriveMirror.hidden = !currentRelease?.googleDriveMirrorAvailable;
}

function showUpdateToast(message) {
    updateToastMessage.innerText = message || 'Клиент готов к запуску.';
    updateToast.classList.add('visible');
    clearTimeout(showUpdateToast.timer);
    showUpdateToast.timer = setTimeout(() => updateToast.classList.remove('visible'), 4500);
}

// Handle check-updates status response
ipcRenderer.on('update-status', (event, data) => {
    if (data.updateRequired) {
        if (data.release) {
            renderRelease(data.release);
        }
        updateModal.style.display = 'flex';
        requestAnimationFrame(syncUpdateNotesOverflow);
        btnPlay.disabled = true;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-cloud-arrow-down"></i> ТРЕБУЕТСЯ ОБНОВЛЕНИЕ</span>';
        btnPlay.style.background = 'linear-gradient(135deg, #c49c72 0%, #a37c56 100%)';
        btnPlay.style.color = '#ffffff';
        
        if (data.error) {
            isUpdating = false;
            btnStartUpdate.disabled = false;
            btnStartUpdate.innerHTML = '<i class="fa-solid fa-rotate-right"></i> ПОВТОРИТЬ ОБНОВЛЕНИЕ';
            btnStartUpdate.style.opacity = '';
            btnStartUpdate.style.cursor = '';
            modalProgressMessage.innerText = `Ошибка: ${data.error}`;
            modalProgressMessage.style.color = '#E3A899';
            showGoogleDriveMirrorIfAvailable();
        }
    } else {
        updateModal.style.display = 'none';
        btnPlay.disabled = false;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> ИГРАТЬ</span>';
        btnPlay.style.background = '';
        btnPlay.style.color = '';
        
        if (data.success && isUpdating) {
            isUpdating = false;
            showUpdateToast(data.release?.successMessage || 'Обновление загружено и установлено.');
            btnPlay.style.animation = 'pulsePlayBtn 1.5s infinite';
            modalProgressContainer.style.display = 'none';
        }
    }
});

// Handle update progress reports from main process
ipcRenderer.on('update-progress', (event, data) => {
    modalProgressContainer.style.display = 'flex';
    modalProgressBarFill.style.width = `${data.progress}%`;
    modalProgressPercent.innerText = `${data.progress}%`;
    modalProgressMessage.innerText = data.message;
});

// Trigger update download
btnStartUpdate.addEventListener('click', () => {
    if (isUpdating) return;
    isUpdating = true;
    
    btnStartUpdate.disabled = true;
    btnStartUpdate.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> ЗАГРУЗКА...';
    btnStartUpdate.style.opacity = '0.6';
    btnStartUpdate.style.cursor = 'not-allowed';
    
    ipcRenderer.send('trigger-update', { gamePath: currentGamePath, releaseId: currentRelease?.id || null });
});

btnGoogleDriveMirror.addEventListener('click', () => {
    btnGoogleDriveMirror.disabled = true;
    ipcRenderer.send('open-google-drive-mirror');
});

ipcRenderer.on('google-drive-mirror-status', (event, data) => {
    btnGoogleDriveMirror.disabled = false;
    if (data.success) {
        modalProgressMessage.innerText = 'Google Drive открыт в браузере. Скачайте нужный файл из папки релиза.';
        modalProgressMessage.style.color = '#99C3A2';
    } else {
        modalProgressMessage.innerText = `Не удалось открыть Google Drive: ${data.error || 'неизвестная ошибка'}`;
        modalProgressMessage.style.color = '#E3A899';
    }
});
