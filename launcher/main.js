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

const hasSingleInstanceLock = app.requestSingleInstanceLock();
if (!hasSingleInstanceLock) {
    app.exit(0);
} else {
    app.on('second-instance', () => {
        if (!mainWindow || mainWindow.isDestroyed()) {
            return;
        }
        if (mainWindow.isMinimized()) {
            mainWindow.restore();
        }
        mainWindow.show();
        mainWindow.focus();
    });
}

const CLIENT_VERSION = '26.1.2';
const CLIENT_PROFILE_PATH = path.join('versions', CLIENT_VERSION, `${CLIENT_VERSION}.json`);
const DEFAULT_API_URL = process.env.ECLIPSE_API_URL || 'https://api.eclipse-roleplay.online';
const DEFAULT_GAME_SERVER_HOST = process.env.ECLIPSE_GAME_SERVER_HOST || '13.51.232.191';
const DEFAULT_GAME_SERVER_PORT = Number(process.env.ECLIPSE_GAME_SERVER_PORT || 25565);
const DISTRIBUTION_BASE_URL = process.env.ECLIPSE_DISTRIBUTION_BASE_URL || 'https://api.eclipse-roleplay.online/dist';
const DISTRIBUTION_MANIFEST_URL = process.env.ECLIPSE_DISTRIBUTION_MANIFEST_URL || joinUrl(DISTRIBUTION_BASE_URL, 'manifests/production.json');
const REMOTE_CLIENT_BASE_URL = process.env.ECLIPSE_CLIENT_BASE_URL || joinUrl(DISTRIBUTION_BASE_URL, 'client');
const LOCAL_CLIENT_SOURCE_ROOT = path.resolve(__dirname, '..', 'plugins', 'RPChat', 'client');
const JAVA_RUNTIME_MAJOR = 25;
const JAVA_DOWNLOAD_PAGE = 'https://adoptium.net/temurin/releases/?version=25';
const MANAGED_CLIENT_MOD_PREFIX = 'eclipse-client-';

const LEGACY_MANAGED_MOD_FILENAMES = [
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
const LEGACY_MANAGED_MOD_SHA1 = new Set([
    'd2a7c3b26354307f9a6d504f16aae5a786a11b7f'
]);

class EclipseMinecraftClient extends Client {
    startMinecraft(launchArguments) {
        const minecraft = childProcess.spawn(
            this.options.javaPath || 'java',
            launchArguments,
            {
                cwd: this.options.overrides.cwd || this.options.root,
                detached: this.options.overrides.detached,
                windowsHide: process.platform === 'win32',
                stdio: ['ignore', 'pipe', 'pipe']
            }
        );
        minecraft.stdout.on('data', data => this.emit('data', data.toString('utf-8')));
        minecraft.stderr.on('data', data => this.emit('data', data.toString('utf-8')));
        minecraft.on('close', code => this.emit('close', code));
        return minecraft;
    }
}

function getTransport(url) {
    return url.startsWith('https:') ? https : http;
}

function joinUrl(baseUrl, relativePath) {
    return `${baseUrl.replace(/\/+$/, '')}/${relativePath.replace(/\\/g, '/').replace(/^\/+/, '')}`;
}

function downloadFile(url, dest, onProgress, timeoutMs = 20000, redirectDepth = 0) {
    return new Promise((resolve, reject) => {
        logLauncher(`[DOWNLOAD] Started: url=${url}, dest=${dest}`);
        const dir = path.dirname(dest);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }
        
        const file = fs.createWriteStream(dest);
        let receivedBytes = 0;
        let totalBytes = 0;
        let lastProgressLogTime = Date.now();

        const request = getTransport(url).get(url, (response) => {
            logLauncher(`[DOWNLOAD] Response received: status=${response.statusCode}, contentLength=${response.headers['content-length']}`);

            if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location && redirectDepth < 4) {
                logLauncher(`[DOWNLOAD] Redirecting to: ${response.headers.location}`);
                response.resume();
                file.close();
                fs.unlink(dest, () => {});
                const redirectUrl = new URL(response.headers.location, url).toString();
                downloadFile(redirectUrl, dest, onProgress, timeoutMs, redirectDepth + 1).then(resolve).catch(reject);
                return;
            }

            if (response.statusCode !== 200) {
                logLauncher(`[DOWNLOAD] Error status ${response.statusCode} for ${url}`);
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
                const now = Date.now();
                if (now - lastProgressLogTime > 5000) {
                    lastProgressLogTime = now;
                    logLauncher(`[DOWNLOAD] Progress: ${receivedBytes}/${totalBytes} bytes (${totalBytes > 0 ? ((receivedBytes/totalBytes)*100).toFixed(1) : '?'}%)`);
                }
            });

            response.pipe(file);

            file.on('finish', () => {
                file.close();
                logLauncher(`[DOWNLOAD] Finished successfully: ${dest}`);
                resolve();
            });

            file.on('error', (err) => {
                logLauncher(`[DOWNLOAD] File stream error for ${dest}: ${err.message}`);
                file.close();
                fs.unlink(dest, () => {});
                reject(err);
            });
        });

        // Set connection & activity timeout
        request.setTimeout(timeoutMs, () => {
            logLauncher(`[DOWNLOAD] Timeout triggered (${timeoutMs / 1000}s inactivity) for ${url}`);
            request.destroy(new Error('Download timed out'));
        });

        // Detailed socket tracing for DNS / TCP / TLS diagnostics
        request.on('socket', (socket) => {
            logLauncher(`[SOCKET] Assigned for ${url}`);
            
            socket.on('lookup', (err, address, family, host) => {
                if (err) {
                    logLauncher(`[DNS] Lookup failed for ${host}: ${err.message}`);
                } else {
                    logLauncher(`[DNS] Resolved ${host} -> ${address}`);
                }
            });
            
            socket.on('connect', () => {
                logLauncher(`[TCP] Connected successfully to server`);
            });
            
            socket.on('secureConnect', () => {
                logLauncher(`[TLS] Secure handshake completed`);
            });
            
            socket.on('error', (err) => {
                logLauncher(`[SOCKET] Error: ${err.message}`);
            });
        });

        request.on('error', (err) => {
            logLauncher(`[DOWNLOAD] HTTP request error for ${url}: ${err.message}`);
            file.close();
            fs.unlink(dest, () => {});
            reject(err);
        });
    });
}

async function downloadFileWithFallback(urls, dest, onProgress, timeoutMs = 10000) {
    let lastError;
    const urlList = Array.isArray(urls) ? urls : [urls];
    for (const url of urlList) {
        try {
            await downloadFile(url, dest, onProgress, timeoutMs);
            return url;
        } catch (error) {
            lastError = error;
            logLauncher(`[FALLBACK] Failed download from ${url}: ${error.message}. Trying next candidate...`);
        }
    }
    throw lastError || new Error('All download sources failed');
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

function readLocalClientManifest() {
    const manifestPath = getLocalClientSource('mods.json');
    if (!fs.existsSync(manifestPath)) {
        return null;
    }
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    return Array.isArray(manifest) ? manifest : null;
}

function fileSha1(filePath) {
    return crypto.createHash('sha1').update(fs.readFileSync(filePath)).digest('hex');
}

function fileSha256(filePath) {
    return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

function verifyDownloadedFile(filePath, descriptor) {
    if (!fs.existsSync(filePath)) {
        throw new Error('Downloaded installer is missing.');
    }

    const stat = fs.statSync(filePath);
    if (descriptor.size && stat.size !== Number(descriptor.size)) {
        throw new Error(`Installer size mismatch: expected ${descriptor.size}, received ${stat.size}.`);
    }

    const expectedSha256 = String(descriptor.sha256 || '').trim().toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(expectedSha256)) {
        throw new Error('Distribution manifest does not contain a valid installer SHA-256.');
    }

    const actualSha256 = fileSha256(filePath);
    if (actualSha256 !== expectedSha256) {
        throw new Error(`Installer SHA-256 mismatch: expected ${expectedSha256}, received ${actualSha256}.`);
    }

    return actualSha256;
}

function startDetachedInstaller(installerPath) {
    return new Promise((resolve, reject) => {
        const installer = childProcess.spawn(
            installerPath,
            ['/S', '--updated'],
            {
                detached: true,
                stdio: 'ignore',
                windowsHide: false
            }
        );

        installer.once('error', reject);
        installer.once('spawn', () => {
            const pid = installer.pid;
            installer.unref();
            resolve(pid);
        });
    });
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

function discoverWindowsJavaExecutables() {
    if (process.platform !== 'win32') {
        return [];
    }

    const programFiles = [process.env.ProgramFiles, process.env['ProgramFiles(x86)']].filter(Boolean);
    const vendorDirectories = ['Java', 'Eclipse Adoptium', 'Microsoft', 'BellSoft'];
    const candidates = [];
    for (const base of programFiles) {
        for (const vendor of vendorDirectories) {
            const vendorRoot = path.join(base, vendor);
            if (!fs.existsSync(vendorRoot)) {
                continue;
            }
            for (const entry of fs.readdirSync(vendorRoot, { withFileTypes: true })) {
                if (!entry.isDirectory()) {
                    continue;
                }
                const javaExe = path.join(vendorRoot, entry.name, 'bin', 'java.exe');
                if (fs.existsSync(javaExe)) {
                    candidates.push(javaExe);
                }
            }
        }
    }
    return candidates.sort((left, right) => right.localeCompare(left, undefined, { numeric: true }));
}

function resolveExecutablePath(executable) {
    if (path.isAbsolute(executable) && fs.existsSync(executable)) {
        return fs.realpathSync(executable);
    }
    if (process.platform === 'win32') {
        const result = childProcess.spawnSync('where.exe', [executable], {
            encoding: 'utf8',
            windowsHide: true
        });
        const match = String(result.stdout || '').split(/\r?\n/).map(line => line.trim()).find(Boolean);
        if (match && fs.existsSync(match)) {
            return fs.realpathSync(match);
        }
    }
    return executable;
}

function getJavaLaunchExecutable(javaExecutable) {
    if (process.platform !== 'win32') {
        return javaExecutable;
    }
    const javaw = path.join(path.dirname(javaExecutable), 'javaw.exe');
    return fs.existsSync(javaw) ? javaw : javaExecutable;
}

function checkJavaExecutable(javaPath) {
    return new Promise((resolve, reject) => {
        const resolvedJavaPath = resolveExecutablePath(javaPath);
        childProcess.execFile(resolvedJavaPath, ['-version'], { timeout: 5000, windowsHide: true }, (error, stdout, stderr) => {
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

            resolve({ javaPath: resolvedJavaPath, major, output });
        });
    });
}

async function resolveJavaExecutable() {
    const config = readConfig();
    const candidates = [
        config.javaPath,
        process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : null,
        ...discoverWindowsJavaExecutables(),
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
        `Не найдена подходящая Java. Для Eclipse нужен Java ${JAVA_RUNTIME_MAJOR} или новее.\n` +
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
    const managedNames = new Set([...requiredNames, ...LEGACY_MANAGED_MOD_FILENAMES]);
    if ([...managedNames].some(filename => {
        return !requiredNames.has(filename) && fs.existsSync(path.join(modsPath, filename));
    })) {
        return true;
    }
    if (!fs.existsSync(modsPath)) {
        return false;
    }
    return fs.readdirSync(modsPath)
        .filter(filename => filename.toLowerCase().endsWith('.jar') && !requiredNames.has(filename))
        .some(filename => filename.startsWith(MANAGED_CLIENT_MOD_PREFIX)
            || LEGACY_MANAGED_MOD_SHA1.has(fileSha1(path.join(modsPath, filename))));
}

function removeObsoleteManagedMods(gamePath, requiredMods) {
    const requiredNames = new Set(requiredMods.map(mod => mod.name));
    const modsPath = path.join(gamePath, 'mods');
    const managedNames = new Set([...requiredNames, ...LEGACY_MANAGED_MOD_FILENAMES]);
    for (const filename of managedNames) {
        if (requiredNames.has(filename)) {
            continue;
        }

        const obsoletePath = path.join(modsPath, filename);
        if (fs.existsSync(obsoletePath)) {
            fs.unlinkSync(obsoletePath);
        }
    }
    if (fs.existsSync(modsPath)) {
        for (const filename of fs.readdirSync(modsPath)) {
            const candidate = path.join(modsPath, filename);
            if (!filename.toLowerCase().endsWith('.jar') || requiredNames.has(filename)) {
                continue;
            }
            if (filename.startsWith(MANAGED_CLIENT_MOD_PREFIX)
                    || LEGACY_MANAGED_MOD_SHA1.has(fileSha1(candidate))) {
                fs.unlinkSync(candidate);
            }
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
    if (!value || value === 'localhost' || value === '127.0.0.1' || value === '::1'
            || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(value)) {
        return DEFAULT_GAME_SERVER_HOST;
    }
    return value;
}

function normalizeStoredApiUrl(url) {
    const value = normalizeApiUrl(url);
    if (value === 'https://eclipse-rp.13-51-232-191.sslip.io') {
        return DEFAULT_API_URL;
    }
    if (/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\]|10\.[^/:]+|192\.168\.[^/:]+|172\.(1[6-9]|2\d|3[01])\.[^/:]+)(?::\d+)?$/i.test(value)) {
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

async function fetchDistributionManifest() {
    const distribution = await requestJson(`${DISTRIBUTION_MANIFEST_URL}?ts=${Date.now()}`, 5000);
    if (!distribution || !distribution.client || !Array.isArray(distribution.client.mods)) {
        throw new Error('Invalid Eclipse distribution manifest');
    }
    return distribution;
}

async function fetchRequiredModsFromSources(apiUrl) {
    try {
        const distribution = await fetchDistributionManifest();
        logLauncher(`Loaded Eclipse distribution manifest from ${DISTRIBUTION_MANIFEST_URL}`);
        return distribution.client.mods;
    } catch (error) {
        logLauncher(`Distribution manifest unavailable: ${error.message}`);
    }

    try {
        const apiList = await requestJson(`${normalizeApiUrl(apiUrl)}/api/required-mods?ts=${Date.now()}`, 5000);
        if (Array.isArray(apiList)) {
            logLauncher(`Loaded client manifest from server API ${apiUrl}`);
            return apiList;
        }
    } catch (error) {
        logLauncher(`Server client manifest unavailable: ${error.message}`);
    }

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

    const ghDistUrl = `https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/manifests/production.json?ts=${Date.now()}`;
    try {
        const ghDistribution = await requestJson(ghDistUrl, 10000);
        if (ghDistribution && ghDistribution.client && Array.isArray(ghDistribution.client.mods)) {
            logLauncher(`Loaded distribution manifest from GitHub fallback`);
            return ghDistribution.client.mods;
        }
    } catch (error) {
        logLauncher(`GitHub distribution manifest unavailable: ${error.message}`);
    }

    const ghModsUrl = `https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/client/mods.json?ts=${Date.now()}`;
    try {
        const ghModsList = await requestJson(ghModsUrl, 10000);
        if (Array.isArray(ghModsList)) {
            logLauncher(`Loaded client manifest from GitHub fallback ${ghModsUrl}`);
            return ghModsList;
        }
    } catch (error) {
        logLauncher(`GitHub client manifest unavailable: ${error.message}`);
    }

    try {
        const localList = readLocalClientManifest();
        if (Array.isArray(localList)) {
            logLauncher(`Loaded client manifest from local source ${getLocalClientSource('mods.json')}`);
            return localList;
        }
    } catch (error) {
        logLauncher(`Local client manifest unavailable: ${error.message}`);
    }

    throw new Error('Client manifest unavailable from remote, server API, and local source.');
}

async function downloadClientAsset(relativePath, dest, apiUrl, onProgress) {
    const cleanPath = relativePath.replace(/\\/g, '/');
    const candidates = [
        joinUrl(normalizeApiUrl(apiUrl), `client/${cleanPath}`),
        joinUrl(REMOTE_CLIENT_BASE_URL, relativePath),
        `https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/client/${cleanPath}`
    ];
    
    let lastError;
    try {
        await downloadFileWithFallback(candidates, dest, onProgress, 10000);
        logLauncher(`Downloaded ${relativePath} successfully`);
        return;
    } catch (error) {
        lastError = error;
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
    candidates.push(joinUrl(normalizeApiUrl(apiUrl), `client/${mod.path}`));
    candidates.push(joinUrl(REMOTE_CLIENT_BASE_URL, mod.path));
    candidates.push(`https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/client/${mod.path}`);

    let lastError;
    for (const url of candidates) {
        try {
            await downloadFile(url, dest, onProgress, 10000);
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
    const defaultPath = fs.existsSync('D:\\eclipse') ? 'D:\\eclipse' : path.join(app.getPath('appData'), '.eclipse-rp');
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
    event.reply('config-data', { gamePath, fullscreen, lastUsername });
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
    try {
        const distribution = await fetchDistributionManifest();
        
        // Check for launcher self-update first
        const currentLauncherVersion = app.getVersion();
        const remoteLauncherVersion = distribution.launcher && distribution.launcher.version;
        if (remoteLauncherVersion && remoteLauncherVersion !== currentLauncherVersion) {
            logLauncher(`Launcher update available: ${currentLauncherVersion} -> ${remoteLauncherVersion}`);
            event.reply('update-status', {
                updateRequired: true,
                isLauncherUpdate: true,
                release: {
                    title: 'Обновление лаунчера',
                    summary: `Доступна новая версия лаунчера ${remoteLauncherVersion} (текущая: ${currentLauncherVersion}). Для продолжения игры необходимо обновить лаунчер.`,
                    buttonLabel: 'ОБНОВИТЬ ЛАУНЧЕР',
                    notes: [
                        'Завершаются только процессы Eclipse RolePlay Launcher текущего пользователя',
                        'Minecraft, Java и другие приложения не затрагиваются',
                        'Перед запуском проверяются размер и SHA-256 установщика'
                    ]
                }
            });
            return;
        }

        const release = distribution.release;
        const config = readConfig();
        const updateRequired = Boolean(
            release
            && release.published === true
            && typeof release.id === 'string'
            && release.id.trim()
            && release.id !== config.lastAppliedReleaseId
        );
        event.reply('update-status', { updateRequired, release: updateRequired ? release : null });
    } catch (error) {
        logLauncher(`Release push check failed quietly: ${error.message}`);
        event.reply('update-status', { updateRequired: false });
    }
});

ipcMain.on('trigger-update', async (event, { gamePath }) => {
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.eclipse-rp');
    const { apiUrl } = getServerSettings();
    let activeRelease = null;
    
    try {
        const distribution = await fetchDistributionManifest();
        
        // Handle launcher self-update download and execution
        const currentLauncherVersion = app.getVersion();
        const remoteLauncherVersion = distribution.launcher && distribution.launcher.version;
        if (remoteLauncherVersion && remoteLauncherVersion !== currentLauncherVersion) {
            const updateProgress = (message, progress) => {
                event.reply('update-progress', { status: 'downloading', progress, message });
            };
            
            updateProgress('Скачивание установщика лаунчера...', 10);
            const installerDest = path.join(app.getPath('temp'), `Eclipse-RolePlay-Launcher-Setup-${remoteLauncherVersion}.exe`);
            
            const installerCandidates = [
                distribution.launcher.url,
                `https://raw.githubusercontent.com/evgeniy111222333/oasis/dist/launcher/stable/Eclipse-RolePlay-Launcher-Setup-${remoteLauncherVersion}.exe`
            ];
            await downloadFileWithFallback(installerCandidates, installerDest, (received, total) => {
                const percent = Math.round((received / total) * 100);
                const mbReceived = (received / (1024 * 1024)).toFixed(1);
                const mbTotal = (total / (1024 * 1024)).toFixed(1);
                updateProgress(`Скачивание установщика лаунчера: ${percent}% [${mbReceived} MB / ${mbTotal} MB]...`, Math.round(10 + percent * 0.8));
            }, 10000);

            const installerSha256 = verifyDownloadedFile(installerDest, distribution.launcher);
            logLauncher(`Launcher setup verified: size=${fs.statSync(installerDest).size}, sha256=${installerSha256}`);
            
            updateProgress('Запуск установщика...', 95);
            const installerPid = await startDetachedInstaller(installerDest);
            logLauncher(`Detached launcher setup started: path=${installerDest}, pid=${installerPid}, args=/S --updated`);
            updateProgress('Установщик запущен. Лаунчер будет перезапущен после обновления.', 100);

            setTimeout(() => {
                logLauncher('Exiting old launcher so the detached installer can replace it.');
                app.exit(0);
            }, 300);
            return;
        }

        let requiredMods;
        requiredMods = distribution.client.mods;
        activeRelease = distribution.release || null;

        
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
        if (activeRelease && activeRelease.published === true && activeRelease.id) {
            const config = readConfig();
            config.lastAppliedReleaseId = activeRelease.id;
            saveConfig(config);
        }
        event.reply('update-status', { updateRequired: false, success: true, release: activeRelease });
    } catch (err) {
        console.error('Update failed:', err);
        event.reply('update-status', { updateRequired: true, error: err.message, release: activeRelease });
    }
});

// Choose folder
ipcMain.on('select-directory', (event) => {
    dialog.showOpenDialog(mainWindow, {
        properties: ['openDirectory'],
        title: 'Выберите папку для установки Eclipse RP'
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
    const launcher = new EclipseMinecraftClient();
    
    // Default fallback path
    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.eclipse-rp');
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
        const launchJavaPath = getJavaLaunchExecutable(javaInfo.javaPath);
        logLauncher(`Using Java ${javaInfo.major} runtime ${javaInfo.javaPath}; launching through ${launchJavaPath}`);

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
            javaPath: launchJavaPath,
            overrides: {
                detached: false
            },
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
            },
            // Make the public HTTPS API available before the first world render.
            // Without this, the client guesses http://<game-host>:25580 and every
            // initial skin request waits for a connection timeout.
            customArgs: [`-Declipse.apiUrl=${normalizeApiUrl(apiUrl)}`]
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
