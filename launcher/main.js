const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const { Client, Authenticator } = require('minecraft-launcher-core');

let mainWindow;
let isGameRunning = false;

const CLIENT_VERSION = '26.1.2';
const CLIENT_PROFILE_PATH = path.join('versions', CLIENT_VERSION, `${CLIENT_VERSION}.json`);
const SERVER_URL = 'http://localhost:25580';
const LOCAL_CLIENT_SOURCE_ROOT = path.resolve(__dirname, '..', 'plugins', 'RPChat', 'client');

const REQUIRED_MODS = [
    { name: 'oasisauth-1.0.0.jar', path: 'mods/oasisauth-1.0.0.jar', sha1: 'df95b07b8c622495f95084d3f9353ffdf08b6796', size: 560242 },
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

function downloadFile(url, dest, onProgress) {
    return new Promise((resolve, reject) => {
        const dir = path.dirname(dest);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
        
        const file = fs.createWriteStream(dest);
        let receivedBytes = 0;
        let totalBytes = 0;

        const request = http.get(url, (response) => {
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

function getClientProfilePath(gamePath) {
    return path.join(gamePath, CLIENT_PROFILE_PATH);
}

function getLocalClientSource(relativePath) {
    return path.join(LOCAL_CLIENT_SOURCE_ROOT, relativePath);
}

function fileSha1(filePath) {
    return crypto.createHash('sha1').update(fs.readFileSync(filePath)).digest('hex');
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

function fetchRequiredModsFromServer() {
    return new Promise((resolve) => {
        const url = `${SERVER_URL}/api/required-mods`;
        http.get(url, (response) => {
            if (response.statusCode !== 200) {
                console.warn(`Server mods API returned status: ${response.statusCode}. Using fallback local mods.`);
                resolve(REQUIRED_MODS);
                return;
            }
            let data = '';
            response.on('data', (chunk) => { data += chunk; });
            response.on('end', () => {
                try {
                    const list = JSON.parse(data);
                    if (Array.isArray(list)) {
                        resolve(list);
                    } else {
                        console.warn('Server returned non-array mods list. Using fallback local mods.');
                        resolve(REQUIRED_MODS);
                    }
                } catch (e) {
                    console.warn('Failed to parse server mods list JSON. Using fallback local mods:', e);
                    resolve(REQUIRED_MODS);
                }
            });
        }).on('error', (err) => {
            console.warn('Failed to fetch required mods from server. Using fallback local mods:', err.message);
            resolve(REQUIRED_MODS);
        });
    });
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
    if (!config.gamePath || config.fullscreen === undefined) {
        config.gamePath = gamePath;
        config.fullscreen = fullscreen;
        saveConfig(config);
    }
    event.reply('config-data', { gamePath, fullscreen });
});

// Save fullscreen toggle state
ipcMain.on('save-fullscreen', (event, val) => {
    const config = readConfig();
    config.fullscreen = val;
    saveConfig(config);
});

// Verification and update handlers
ipcMain.on('check-updates', async (event, { gamePath }) => {
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.oasis-rp');

    const requiredMods = await fetchRequiredModsFromServer();

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
    
    try {
        // Fetch dynamic mods list from server
        const requiredMods = await fetchRequiredModsFromServer();
        
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
            const localSource = getLocalClientSource(CLIENT_PROFILE_PATH);
            if (fs.existsSync(localSource)) {
                fs.mkdirSync(path.dirname(versionJsonPath), { recursive: true });
                fs.copyFileSync(localSource, versionJsonPath);
            } else {
                await downloadFile(`${SERVER_URL}/client/${CLIENT_PROFILE_PATH.replace(/\\/g, '/')}`, versionJsonPath);
            }
        }
        currentStep++;

        // 2. Download or repair managed mods
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                const localSource = getLocalClientSource(mod.path);
                if (fs.existsSync(localSource)) {
                    updateProgress(`Копирование мода: ${mod.name}...`, Math.round((currentStep / totalSteps) * 100));
                    fs.mkdirSync(path.dirname(modLocalPath), { recursive: true });
                    fs.copyFileSync(localSource, modLocalPath);
                } else {
                    updateProgress(`Загрузка мода: ${mod.name} (0%)...`, Math.round((currentStep / totalSteps) * 100));
                    await downloadFile(`${SERVER_URL}/client/${mod.path}`, modLocalPath, (received, total) => {
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
    logLauncher(`Launching ${CLIENT_VERSION} for ${username} in path: ${gamePath}`);
    const launcher = new Client();
    
    // Default fallback path
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.oasis-rp');

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
        // Quick fallback verification (just in case they deleted files since last check)
        const versionJsonPath = getClientProfilePath(activeGamePath);

        if (!isClientProfileValid(versionJsonPath)) {
            const localSource = getLocalClientSource(CLIENT_PROFILE_PATH);
            if (fs.existsSync(localSource)) {
                fs.mkdirSync(path.dirname(versionJsonPath), { recursive: true });
                fs.copyFileSync(localSource, versionJsonPath);
            } else {
                await downloadFile(`${SERVER_URL}/client/${CLIENT_PROFILE_PATH.replace(/\\/g, '/')}`, versionJsonPath);
            }
        }

        // Quick mod check
        const requiredMods = await fetchRequiredModsFromServer();
        removeObsoleteManagedMods(activeGamePath, requiredMods);
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                const localSource = getLocalClientSource(mod.path);
                if (fs.existsSync(localSource)) {
                    fs.mkdirSync(path.dirname(modLocalPath), { recursive: true });
                    fs.copyFileSync(localSource, modLocalPath);
                } else {
                    await downloadFile(`${SERVER_URL}/client/${mod.path}`, modLocalPath);
                }
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
            javaPath: 'javaw', // Launch using javaw to prevent CMD window from opening
            server: {
                host: "localhost",
                port: 25565
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
