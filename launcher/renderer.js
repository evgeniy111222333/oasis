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

let currentGamePath = '';

// Load config on startup
ipcRenderer.send('get-config');
ipcRenderer.on('config-data', (event, data) => {
    currentGamePath = data.gamePath;
    gamePathInput.value = currentGamePath;
    fullscreenInput.checked = data.fullscreen || false;
    ipcRenderer.send('check-updates', { gamePath: currentGamePath });
});

// Settings toggle
btnSettings.addEventListener('click', () => {
    settingsModal.style.display = 'flex';
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
        alert('Будь ласка, введіть ваш нікнейм!');
        return;
    }

    // Block play button and show progress bar
    btnPlay.disabled = true;
    progressContainer.style.display = 'flex';
    progressBarFill.style.width = '0%';
    progressPercent.innerText = '0%';
    progressMessage.innerText = 'Ініціалізація завантажувача...';

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
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-gamepad"></i> В ГРІ</span>';
        btnPlay.classList.add('in-game-style');
        
        setTimeout(() => {
            progressContainer.style.display = 'none';
        }, 1500);
    } else if (data.status === 'error') {
        btnPlay.disabled = false;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> Р“Р РђРўР</span>';
        btnPlay.classList.remove('in-game-style');
        progressContainer.style.display = 'flex';
    }
});

// Restore play button once the game window is closed
ipcRenderer.on('game-closed', () => {
    btnPlay.disabled = false;
    btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> ГРАТИ</span>';
    btnPlay.classList.remove('in-game-style');
});

// Dynamic server status & online query from plugin API
async function updateServerStatus() {
    const statusDot = document.querySelector('.status-dot');
    const statusText = document.querySelector('.status-text');
    const onlineCount = document.querySelector('.online-count');
    
    try {
        const response = await fetch('http://localhost:25580/api/server-status');
        const data = await response.json();
        
        if (data.success && data.status === 'online') {
            statusDot.style.backgroundColor = '#99C3A2'; // Success green
            statusDot.style.boxShadow = '0 0 8px #99C3A2';
            statusText.innerText = 'Сервер працює в штатному режимі';
            onlineCount.innerText = `${data.onlinePlayers} / ${data.maxPlayers}`;
        } else {
            statusDot.style.backgroundColor = '#E3A899'; // Offline red
            statusDot.style.boxShadow = '0 0 8px #E3A899';
            statusText.innerText = 'Сервер не відповідає';
            onlineCount.innerText = '-- / --';
        }
    } catch (error) {
        statusDot.style.backgroundColor = '#E3A899'; // Offline red
        statusDot.style.boxShadow = '0 0 8px #E3A899';
        statusText.innerText = 'Сервер вимкнено';
        onlineCount.innerText = '-- / --';
    }
}

// Poll server status every 4 seconds
setInterval(updateServerStatus, 4000);
updateServerStatus(); // initial check

// Update modal UI elements
const updateModal = document.getElementById('updateModal');
const btnStartUpdate = document.getElementById('btnStartUpdate');
const modalProgressContainer = document.getElementById('modalProgressContainer');
const modalProgressBarFill = document.getElementById('modalProgressBarFill');
const modalProgressMessage = document.getElementById('modalProgressMessage');
const modalProgressPercent = document.getElementById('modalProgressPercent');

let isUpdating = false;

// Handle check-updates status response
ipcRenderer.on('update-status', (event, data) => {
    if (data.updateRequired) {
        updateModal.style.display = 'flex';
        btnPlay.disabled = true;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-cloud-arrow-down"></i> ПОТРІБНЕ ОНОВЛЕННЯ</span>';
        btnPlay.style.background = 'linear-gradient(135deg, #c49c72 0%, #a37c56 100%)';
        btnPlay.style.color = '#ffffff';
        
        if (data.error) {
            isUpdating = false;
            btnStartUpdate.disabled = false;
            btnStartUpdate.innerHTML = '<i class="fa-solid fa-rotate-right"></i> ПОВТОРИТИ ОНОВЛЕННЯ';
            btnStartUpdate.style.opacity = '';
            btnStartUpdate.style.cursor = '';
            modalProgressMessage.innerText = `Помилка: ${data.error}`;
            modalProgressMessage.style.color = '#E3A899';
        }
    } else {
        updateModal.style.display = 'none';
        btnPlay.disabled = false;
        btnPlay.innerHTML = '<span class="btn-text"><i class="fa-solid fa-play"></i> ГРАТИ</span>';
        btnPlay.style.background = '';
        btnPlay.style.color = '';
        
        if (data.success && isUpdating) {
            isUpdating = false;
            // Highlight play button to denote success
            btnPlay.style.animation = 'pulsePlayBtn 1.5s infinite';
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
    btnStartUpdate.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> ЗАВАНТАЖЕННЯ...';
    btnStartUpdate.style.opacity = '0.6';
    btnStartUpdate.style.cursor = 'not-allowed';
    
    ipcRenderer.send('trigger-update', { gamePath: currentGamePath });
});
