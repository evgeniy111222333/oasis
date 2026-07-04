const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const childProcess = require('child_process');
const { Client, Authenticator } = require('minecraft-launcher-core');

let mainWindow;
let isGameRunning = false;

const CLIENT_VERSION = '26.1.2';
const CLIENT_PROFILE_PATH = path.join('versions', CLIENT_VERSION, `${CLIENT_VERSION}.json`);
const DEFAULT_API_URL = process.env.OASIS_API_URL || 'http://192.168.0.241:25580';
const DEFAULT_GAME_SERVER_HOST = process.env.OASIS_GAME_SERVER_HOST || '192.168.0.241';
const DEFAULT_GAME_SERVER_PORT = Number(process.env.OASIS_GAME_SERVER_PORT || 25565);
const REMOTE_CLIENT_BASE_URL = process.env.OASIS_CLIENT_BASE_URL || 'https://raw.githubusercontent.com/evgeniy111222333/oasis/dev/plugins/RPChat/client';
const LOCAL_CLIENT_SOURCE_ROOT = path.resolve(__dirname, '..', 'plugins', 'RPChat', 'client');
const JAVA_RUNTIME_MAJOR = 25;
const JAVA_DOWNLOAD_PAGE = 'https://adoptium.net/temurin/releases/?version=25';

const REQUIRED_MODS = [
    { name: 'oasisauth-1.0.0.jar', path: 'mods/oasisauth-1.0.0.jar', sha1: 'a3b08b4fe21b1890358ca42b0fe2d03327c33ab1', size: 541102 },
    { name: 'mcef_fabric_2.2.0_MC_26.1.1.jar', path: 'mods/mcef_fabric_2.2.0_MC_26.1.1.jar', sha1: '3168366b5cfce5302a53635674dcee443bb7eeca', size: 453664 },
    { name: 'fabric-api-0.153.0+26.1.2.jar', path: 'mods/fabric-api-0.153.0+26.1.2.jar', sha1: '5d984764e54f1f1db397d3f76429a0f15e591845', size: 2504357 },
    { name: 'fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar', path: 'mods/fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar', sha1: '2bc17bb4275cc70a12e4ac35d139a71a30845720', size: 8076848 },
    { name: 'yet_another_config_lib_v3-3.9.5+26.1-fabric.jar', path: 'mods/yet_another_config_lib_v3-3.9.5+26.1-fabric.jar', sha1: 'dd0b7f266eced755bb48d5213df309f07d71de5b', size: 1121083 },
    { name: 'sodium-fabric-0.8.12+mc26.1.2.jar', path: 'mods/sodium-fabric-0.8.12+mc26.1.2.jar', sha1: 'cd6c6236f0dcff03c7148414db220de32c934b5a', size: 1844226 },
    { name: 'iris-fabric-1.10.9+mc26.1.1.jar', path: 'mods/iris-fabric-1.10.9+mc26.1.1.jar', sha1: 'c30e04509a1b284372cb9037b07714d4223ae91a', size: 2803860 },
    { name: 'zoomify-2.16.1+26.1.jar', path: 'mods/zoomify-2.16.1+26.1.jar', sha1: 'c180ae8cf90da1abd67c26b5c5e7bf5d795c3b1d', size: 561967 },
    { name: 'entity_texture_features_26.1-fabric-7.1.jar', path: 'mods/entity_texture_features_26.1-fabric-7.1.jar', sha1: 'ff6284b53ad23e06bc082d1e05e8828e47455126', size: 740706 },
    { name: 'entity_model_features-3.2.4-26.1-fabric.jar', path: 'mods/entity_model_features-3.2.4-26.1-fabric.jar', sha1: '7a43e5c92b87e360bfa0156870f2097549e3732d', size: 577617 }
];

const MANAGED_MOD_FILENAMES = [
    ...REQUIRED_MODS.map(mod => mod.name),
    'fabric-api-0.106.1+1.21.2.jar',
    'fabric-api-0.114.1+1.21.3.jar',
    'sodium-fabric-0.6.13+mc1.21.3.jar',
    'iris-fabric-1.8.0+mc1.21.3.jar',
    'Zoomify-2.14.6+1.21.3.jar',
    'entity_texture_features_1.21.3-fabric-7.1.jar',
    'entity_model_features-3.2.4-1.21.3-fabric.jar',
    'mcef-2.1.0.jar',
    'continuity-3.0.1-beta.2+26.1.jar'
];

function getTransport(url) {
    return url.startsWith('https:') ? https : http;
}

function joinUrl(baseUrl, relativePath) {
    return `${baseUrl.replace(/\/+$/, '')}/${relativePath.replace(/\\/g, '/').replace(/^\/+/, '')}`;
}

function downloadFile(url, dest, onProgress, redirectDepth = 0) {
    return new Promise((resolve, reject) => {
        const dir = path.dirname(dest);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
        
        const file = fs.createWriteStream(dest);
        let receivedBytes = 0;
        let totalBytes = 0;

        const request = getTransport(url).get(url, (response) => {
            if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location && redirectDepth < 4) {
                response.resume();
                file.close();
                fs.unlink(dest, () => {});
                const redirectUrl = new URL(response.headers.location, url).toString();
                downloadFile(redirectUrl, dest, onProgress, redirectDepth + 1).then(resolve).catch(reject);
                return;
            }

            if (response.statusCode !== 200) {
                response.resume();
                file.close();
                fs.unlink(dest, () => {});
                reject(new Error(`Server returned status code ${response.statusCode}`));
                return;
            }

            totalBytes = parseInt(response.headers['content-length'], 10) || 0;

            response.on('data', (chunk) => {
                receivedBytes += chunk.length;
                if (onProgress && totalBytes > 0) {
                    onProgress(receivedBytes, totalBytes);
                }
            });

            response.pipe(file);

            file.on('finish', () => {
                file.close();
                resolve();
            });

            file.on('error', (err) => {
                file.close();
                fs.unlink(dest, () => {});
                reject(err);
            });
        });

        request.on('error', (err) => {
            file.close();
            fs.unlink(dest, () => {});
            reject(err);
        });
    });
}

function requestJson(url, timeoutMs = 3500, redirectDepth = 0) {
    return new Promise((resolve, reject) => {
        const request = getTransport(url).get(url, (response) => {
            if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location && redirectDepth < 4) {
                response.resume();
                const redirectUrl = new URL(response.headers.location, url).toString();
                requestJson(redirectUrl, timeoutMs, redirectDepth + 1).then(resolve).catch(reject);
                return;
            }

            if (response.statusCode !== 200) {
                response.resume();
                reject(new Error(`HTTP ${response.statusCode}`));
                return;
            }

            let data = '';
            response.setEncoding('utf8');
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                try {
                    resolve(JSON.parse(data));
                } catch (error) {
                    reject(error);
                }
            });
        });

        request.setTimeout(timeoutMs, () => {
            request.destroy(new Error('Request timed out'));
        });
        request.on('error', reject);
    });
}

function getClientProfilePath(gamePath) {
    return path.join(gamePath, CLIENT_PROFILE_PATH);
}

function getLocalClientSource(relativePath) {
    return path.join(LOCAL_CLIENT_SOURCE_ROOT, relativePath);
}

function fileSha1(filePath) {
    return crypto.createHash('sha1').update(fs.readFileSync(filePath)).digest('hex');
}

function parseJavaMajor(versionOutput) {
    const match = String(versionOutput || '').match(/(?:openjdk|java)\s+version\s+"([^"]+)"/i)
        || String(versionOutput || '').match(/version\s+"([^"]+)"/i);
    if (!match) {
        return null;
    }

    const version = match[1];
    if (version.startsWith('1.')) {
        return Number(version.split('.')[1]);
    }
    return Number(version.split('.')[0]);
}

function checkJavaExecutable(javaPath) {
    return new Promise((resolve, reject) => {
        childProcess.execFile(javaPath, ['-version'], { timeout: 5000 }, (error, stdout, stderr) => {
            if (error) {
                reject(error);
                return;
            }

            const output = `${stdout || ''}\n${stderr || ''}`;
            const major = parseJavaMajor(output);
            if (!major) {
                reject(new Error('Cannot detect Java version'));
                return;
            }

            if (major < JAVA_RUNTIME_MAJOR) {
                reject(new Error(`Installed Java version is ${major}, required ${JAVA_RUNTIME_MAJOR}+`));
                return;
            }

            resolve({ javaPath, major, output });
        });
    });
}

async function resolveJavaExecutable() {
    const config = readConfig();
    const candidates = [
        config.javaPath,
        'java'
    ].filter(Boolean);
    const errors = [];

    for (const candidate of candidates) {
        try {
            return await checkJavaExecutable(candidate);
        } catch (error) {
            errors.push(`${candidate}: ${error.message}`);
        }
    }

    throw new Error(
        `Не найдена подходящая Java. Для Oasis нужен Java ${JAVA_RUNTIME_MAJOR} или новее.\n` +
        `Скачайте и установите Temurin/OpenJDK ${JAVA_RUNTIME_MAJOR} с официальной страницы: ${JAVA_DOWNLOAD_PAGE}\n` +
        `После установки перезапустите лаунчер. Детали проверки: ${errors.join(' | ') || 'java не найдена в PATH'}`
    );
}

function isManagedFileValid(filePath, descriptor) {
    if (!fs.existsSync(filePath)) {
        return false;
    }

    const stat = fs.statSync(filePath);
    if (descriptor.size && stat.size !== descriptor.size) {
        return false;
    }

    if (descriptor.sha1 && fileSha1(filePath) !== descriptor.sha1.toLowerCase()) {
        return false;
    }

    return true;
}

function isClientProfileValid(profilePath) {
    if (!fs.existsSync(profilePath)) {
        return false;
    }

    try {
        const profile = JSON.parse(fs.readFileSync(profilePath, 'utf8'));
        const libraries = Array.isArray(profile.libraries) ? profile.libraries : [];
        const libraryNames = libraries.map(lib => lib.name || '');
        return profile.id === CLIENT_VERSION
            && profile.mainClass === 'net.fabricmc.loader.impl.launch.knot.KnotClient'
            && profile.downloads
            && profile.downloads.client
            && libraryNames.includes('net.fabricmc:fabric-loader:0.19.3')
            && !libraryNames.includes('net.fabricmc:intermediary:1.21.2');
    } catch (e) {
        return false;
    }
}

function hasObsoleteManagedMods(gamePath, requiredMods) {
    const requiredNames = new Set(requiredMods.map(mod => mod.name));
    const modsPath = path.join(gamePath, 'mods');
    return MANAGED_MOD_FILENAMES.some(filename => {
        return !requiredNames.has(filename) && fs.existsSync(path.join(modsPath, filename));
    });
}

function removeObsoleteManagedMods(gamePath, requiredMods) {
    const requiredNames = new Set(requiredMods.map(mod => mod.name));
    const modsPath = path.join(gamePath, 'mods');
    for (const filename of MANAGED_MOD_FILENAMES) {
        if (requiredNames.has(filename)) {
            continue;
        }

        const obsoletePath = path.join(modsPath, filename);
        if (fs.existsSync(obsoletePath)) {
            fs.unlinkSync(obsoletePath);
        }
    }
}

const CONFIG_PATH = path.join(app.getPath('userData'), 'launcher-config.json');
const DEBUG_LOG_PATH = path.join(app.getPath('userData'), 'launcher-debug.log');

function logLauncher(message) {
    const line = `[${new Date().toISOString()}] ${message}\n`;
    console.log(message);
    try {
        fs.appendFileSync(DEBUG_LOG_PATH, line, 'utf8');
    } catch (e) {
        console.error('Failed to write launcher debug log:', e);
    }
}

function normalizeApiUrl(url) {
    return String(url || DEFAULT_API_URL).trim().replace(/\/+$/, '') || DEFAULT_API_URL;
}

function normalizeGameHost(host) {
    const value = String(host || DEFAULT_GAME_SERVER_HOST).trim();
    if (!value || value === 'localhost' || value === '127.0.0.1' || value === '::1') {
        return DEFAULT_GAME_SERVER_HOST;
    }
    return value;
}

function normalizeStoredApiUrl(url) {
    const value = normalizeApiUrl(url);
    if (/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/i.test(value)) {
        return DEFAULT_API_URL;
    }
    return value;
}

function getServerSettings(config = readConfig()) {
    return {
        gameHost: normalizeGameHost(config.serverHost),
        gamePort: Number(config.serverPort || DEFAULT_GAME_SERVER_PORT) || DEFAULT_GAME_SERVER_PORT,
        apiUrl: normalizeStoredApiUrl(config.apiUrl)
    };
}

async function fetchRequiredModsFromSources(apiUrl) {
    const manifestUrl = `${joinUrl(REMOTE_CLIENT_BASE_URL, 'mods.json')}?ts=${Date.now()}`;
    try {
        const remoteList = await requestJson(manifestUrl, 5000);
        if (Array.isArray(remoteList)) {
            logLauncher(`Loaded client manifest from ${manifestUrl}`);
            return remoteList;
        }
    } catch (error) {
        logLauncher(`Remote client manifest unavailable: ${error.message}`);
    }

    try {
        const apiList = await requestJson(`${normalizeApiUrl(apiUrl)}/api/required-mods?ts=${Date.now()}`, 2500);
        if (Array.isArray(apiList)) {
            logLauncher(`Loaded client manifest from server API ${apiUrl}`);
            return apiList;
        }
    } catch (error) {
        logLauncher(`Server client manifest unavailable: ${error.message}`);
    }

    logLauncher('Using embedded client manifest fallback.');
    return REQUIRED_MODS;
}

async function downloadClientAsset(relativePath, dest, apiUrl, onProgress) {
    const candidates = [
        joinUrl(REMOTE_CLIENT_BASE_URL, relativePath),
        joinUrl(normalizeApiUrl(apiUrl), `client/${relativePath.replace(/\\/g, '/')}`)
    ];
    let lastError;

    for (const url of candidates) {
        try {
            await downloadFile(url, dest, onProgress);
            logLauncher(`Downloaded ${relativePath} from ${url}`);
            return;
        } catch (error) {
            lastError = error;
            logLauncher(`Failed to download ${relativePath} from ${url}: ${error.message}`);
        }
    }

    const localSource = getLocalClientSource(relativePath);
    if (fs.existsSync(localSource)) {
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.copyFileSync(localSource, dest);
        logLauncher(`Copied ${relativePath} from local development source.`);
        return;
    }

    throw lastError || new Error(`No source available for ${relativePath}`);
}

async function repairManagedMod(mod, dest, apiUrl, onProgress) {
    const candidates = [];
    if (mod.url) {
        candidates.push(mod.url);
    }
    candidates.push(joinUrl(REMOTE_CLIENT_BASE_URL, mod.path));
    candidates.push(joinUrl(normalizeApiUrl(apiUrl), `client/${mod.path}`));

    let lastError;
    for (const url of candidates) {
        try {
            await downloadFile(url, dest, onProgress);
            if (isManagedFileValid(dest, mod)) {
                logLauncher(`Downloaded ${mod.name} from ${url}`);
                return;
            }
            lastError = new Error(`${mod.name} checksum mismatch after download`);
            fs.unlink(dest, () => {});
        } catch (error) {
            lastError = error;
            logLauncher(`Failed to download ${mod.name} from ${url}: ${error.message}`);
        }
    }

    const localSource = getLocalClientSource(mod.path);
    if (fs.existsSync(localSource)) {
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.copyFileSync(localSource, dest);
        if (isManagedFileValid(dest, mod)) {
            logLauncher(`Copied ${mod.name} from local development source.`);
            return;
        }
        fs.unlink(dest, () => {});
    }

    throw lastError || new Error(`No valid source available for ${mod.name}`);
}

function readConfig() {
    if (fs.existsSync(CONFIG_PATH)) {
        try {
            return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
        } catch (e) {
            return {};
        }
    }
    return {};
}

function saveConfig(config) {
    try {
        fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), 'utf8');
    } catch (e) {
        console.error('Failed to write launcher config:', e);
    }
}

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 950,
        height: 580,
        frame: false, // Frameless window for premium design
        resizable: false,
        backgroundColor: '#12100f',
        icon: path.join(__dirname, 'launcher_logo.png'),
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false
        }
    });

    mainWindow.loadFile('index.html');

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

app.whenReady().then(() => {
    createWindow();

    app.on('activate', () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createWindow();
        }
    });
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

// IPC communication handlers
ipcMain.on('window-minimize', () => {
    if (mainWindow) mainWindow.minimize();
});

ipcMain.on('window-close', () => {
    app.quit();
});

// Load configuration
ipcMain.on('get-config', (event) => {
    const config = readConfig();
    const defaultPath = fs.existsSync('D:\\oasis') ? 'D:\\oasis' : path.join(app.getPath('appData'), '.oasis-rp');
    const gamePath = config.gamePath || defaultPath;
    const fullscreen = config.fullscreen || false;
    const lastUsername = String(config.lastUsername || '').trim();
    const serverSettings = getServerSettings(config);
    config.gamePath = gamePath;
    config.fullscreen = fullscreen;
    config.lastUsername = lastUsername;
    config.serverHost = serverSettings.gameHost;
    config.serverPort = serverSettings.gamePort;
    config.apiUrl = serverSettings.apiUrl;
    saveConfig(config);
    event.reply('config-data', { gamePath, fullscreen, lastUsername, ...serverSettings });
});

// Save fullscreen toggle state
ipcMain.on('save-fullscreen', (event, val) => {
    const config = readConfig();
    config.fullscreen = val;
    saveConfig(config);
});

ipcMain.on('save-username', (event, username) => {
    const cleanUsername = String(username || '').trim();
    const config = readConfig();
    if (cleanUsername) {
        config.lastUsername = cleanUsername;
    } else {
        delete config.lastUsername;
    }
    saveConfig(config);
});

ipcMain.on('save-server-settings', (event, settings) => {
    const config = readConfig();
    config.serverHost = normalizeGameHost(settings.serverHost);
    config.serverPort = Number(settings.serverPort || DEFAULT_GAME_SERVER_PORT) || DEFAULT_GAME_SERVER_PORT;
    config.apiUrl = normalizeStoredApiUrl(settings.apiUrl);
    saveConfig(config);
    event.reply('config-data', {
        gamePath: config.gamePath,
        fullscreen: config.fullscreen || false,
        lastUsername: String(config.lastUsername || '').trim(),
        ...getServerSettings(config)
    });
});

ipcMain.on('get-server-status', async (event) => {
    const { apiUrl } = getServerSettings();
    try {
        const data = await requestJson(`${apiUrl}/api/server-status?ts=${Date.now()}`, 1400);
        event.reply('server-status-data', data);
    } catch (error) {
        event.reply('server-status-data', { success: false, status: 'offline', error: error.message });
    }
});

// Verification and update handlers
ipcMain.on('check-updates', async (event, { gamePath }) => {
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.oasis-rp');
    const { apiUrl } = getServerSettings();

    const requiredMods = await fetchRequiredModsFromSources(apiUrl);

    if (!isClientProfileValid(getClientProfilePath(activeGamePath))) {
        event.reply('update-status', { updateRequired: true });
        return;
    }

    if (hasObsoleteManagedMods(activeGamePath, requiredMods)) {
        event.reply('update-status', { updateRequired: true });
        return;
    }

    for (const mod of requiredMods) {
        const modLocalPath = path.join(activeGamePath, mod.path);
        if (!isManagedFileValid(modLocalPath, mod)) {
            event.reply('update-status', { updateRequired: true });
            return;
        }
    }

    event.reply('update-status', { updateRequired: false });
});

ipcMain.on('trigger-update', async (event, { gamePath }) => {
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.oasis-rp');
    const { apiUrl } = getServerSettings();
    
    try {
        // Fetch dynamic mods list from remote manifest first, then server API.
        const requiredMods = await fetchRequiredModsFromSources(apiUrl);
        
        let totalSteps = 1 + requiredMods.length;
        let currentStep = 0;

        const updateProgress = (message, progress) => {
            event.reply('update-progress', { status: 'downloading', progress, message });
        };

        removeObsoleteManagedMods(activeGamePath, requiredMods);

        // 1. Download the Fabric 26.1.2 profile if missing or stale
        const versionJsonPath = getClientProfilePath(activeGamePath);
        if (!isClientProfileValid(versionJsonPath)) {
            updateProgress('Загрузка профиля запуска...', Math.round((currentStep / totalSteps) * 100));
            await downloadClientAsset(CLIENT_PROFILE_PATH, versionJsonPath, apiUrl);
        }
        currentStep++;

        // 2. Download or repair managed mods
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                updateProgress(`Загрузка мода: ${mod.name} (0%)...`, Math.round((currentStep / totalSteps) * 100));
                await repairManagedMod(mod, modLocalPath, apiUrl, (received, total) => {
                    const filePercent = Math.round((received / total) * 100);
                    const kbReceived = Math.round(received / 1024);
                    const kbTotal = Math.round(total / 1024);
                    const subProgress = Math.round(((currentStep + (received / total)) / totalSteps) * 100);
                    event.reply('update-progress', {
                        status: 'downloading',
                        progress: subProgress,
                        message: `Загрузка мода: ${mod.name} (${filePercent}%) [${kbReceived} KB / ${kbTotal} KB]...`
                    });
                });
            }
            currentStep++;
        }

        updateProgress('Клиент успешно обновлен!', 100);
        event.reply('update-status', { updateRequired: false, success: true });
    } catch (err) {
        console.error('Update failed:', err);
        event.reply('update-status', { updateRequired: true, error: err.message });
    }
});

// Choose folder
ipcMain.on('select-directory', (event) => {
    dialog.showOpenDialog(mainWindow, {
        properties: ['openDirectory'],
        title: 'Выберите папку для установки Oasis RP'
    }).then(result => {
        if (!result.canceled && result.filePaths.length > 0) {
            const selectedPath = result.filePaths[0];
            const config = readConfig();
            config.gamePath = selectedPath;
            saveConfig(config);
            event.reply('selected-directory', selectedPath);
        }
    }).catch(err => {
        console.error('Directory selector error:', err);
    });
});

// Real Minecraft Launch Sequence
ipcMain.on('launch-game', async (event, { username, gamePath, fullscreen }) => {
    if (isGameRunning) {
        console.log('Game is already running, ignoring launch request.');
        return;
    }
    isGameRunning = true;

    const launchDebug = [];
    const config = readConfig();
    config.lastUsername = String(username || '').trim();
    saveConfig(config);
    logLauncher(`Launching ${CLIENT_VERSION} for ${username} in path: ${gamePath}`);
    const launcher = new Client();
    
    // Default fallback path
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.oasis-rp');
    const { gameHost, gamePort, apiUrl } = getServerSettings();

    if (!fs.existsSync(activeGamePath)) {
        fs.mkdirSync(activeGamePath, { recursive: true });
    }

    launcher.on('debug', (e) => {
        launchDebug.push(String(e));
        logLauncher(`[MCLC Debug] ${e}`);
    });
    launcher.on('data', (e) => {
        launchDebug.push(String(e));
        logLauncher(`[MCLC Output] ${e}`);
    });

    launcher.on('progress', (e) => {
        const percent = Math.round((e.task / e.total) * 100);
        event.reply('launch-status', {
            status: 'downloading',
            progress: percent,
            message: `Загрузка ресурсов: ${e.type} (${e.task}/${e.total})`
        });
    });

    launcher.on('download-status', (e) => {
        event.reply('launch-status', {
            status: 'downloading',
            progress: 90,
            message: `Скачивание библиотек: ${e.type}...`
        });
    });

    launcher.on('close', (code) => {
        logLauncher(`Game process exited with code ${code}`);
        isGameRunning = false;
        event.reply('game-closed'); // Reset play button state in UI
    });

    try {
        event.reply('launch-status', {
            status: 'downloading',
            progress: 3,
            message: `Проверка Java ${JAVA_RUNTIME_MAJOR}+...`
        });
        const javaInfo = await resolveJavaExecutable();
        logLauncher(`Using Java ${javaInfo.major} at ${javaInfo.javaPath}`);

        // Quick fallback verification (just in case they deleted files since last check)
        const versionJsonPath = getClientProfilePath(activeGamePath);

        if (!isClientProfileValid(versionJsonPath)) {
            await downloadClientAsset(CLIENT_PROFILE_PATH, versionJsonPath, apiUrl);
        }

        // Quick mod check
        const requiredMods = await fetchRequiredModsFromSources(apiUrl);
        removeObsoleteManagedMods(activeGamePath, requiredMods);
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                await repairManagedMod(mod, modLocalPath, apiUrl);
            }
        }

        const authSession = await Authenticator.getAuth(username);

        // Force Minecraft language to Russian
        try {
            const optionsPath = path.join(activeGamePath, 'options.txt');
            let optionsContent = '';
            if (fs.existsSync(optionsPath)) {
                optionsContent = fs.readFileSync(optionsPath, 'utf8');
                if (optionsContent.includes('lang:')) {
                    optionsContent = optionsContent.replace(/lang:[a-zA-Z_]+/g, 'lang:ru_ru');
                } else {
                    optionsContent += '\nlang:ru_ru\n';
                }
            } else {
                optionsContent = 'lang:ru_ru\n';
            }
            fs.writeFileSync(optionsPath, optionsContent, 'utf8');
        } catch (e) {
            console.error('Failed to force language in options.txt:', e);
        }

        // MCLC launch options
        let opts = {
            clientPackage: null,
            authorization: authSession,
            root: activeGamePath,
            javaPath: javaInfo.javaPath,
            server: {
                host: gameHost,
                port: gamePort
            },
            window: {
                fullscreen: fullscreen
            },
            version: {
                number: CLIENT_VERSION,
                type: "release",
                custom: CLIENT_VERSION
            },
            memory: {
                max: "4G",
                min: "2G"
            }
        };

        const minecraftProcess = await launcher.launch(opts);
        if (!minecraftProcess) {
            const lastDetails = launchDebug.slice(-8).join(' | ');
            throw new Error(`Minecraft process did not start. ${lastDetails || 'No launcher details were reported.'}`);
        }
        logLauncher(`Minecraft process started with pid ${minecraftProcess.pid}`);

        // Notify UI that the game has successfully launched
        event.reply('launch-status', {
            status: 'success',
            progress: 100,
            message: 'Игра запущена! Приятной игры.'
        });

        setTimeout(() => {
            if (mainWindow) {
                mainWindow.minimize(); // Minimize launcher window while playing
            }
        }, 1500);
    } catch (err) {
        logLauncher(`Launch failed: ${err.stack || err.message}`);
        isGameRunning = false; // Reset flag on launch failure
        event.reply('launch-status', {
            status: 'error',
            progress: 0,
            message: `Ошибка запуска: ${err.message}`
        });
    }
});
