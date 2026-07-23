const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const childProcess = require('child_process');
const { Client } = require('minecraft-launcher-core');
const {
    OFFLINE_DEVELOPER_ARGUMENT,
    createOfflineAuthorization
} = require('./offline-auth');
const {
    getOptionalMods,
    buildOptionalModViewFromCatalog,
    setOptionalModPreference,
    buildFabricDisableArgument
} = require('./optional-mods');
const updateNetwork = require('./update-network');
const { UpdateOperationMutex } = require('./update-operation-mutex');

let mainWindow;
let isGameRunning = false;
const updateOperationMutex = new UpdateOperationMutex();

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
const R2_DISTRIBUTION_BASE_URL = 'https://dist.eclipse-roleplay.online';
const GITHUB_DISTRIBUTION_BASE_URL = 'https://raw.githubusercontent.com/evgeniy111222333/oasis/dist';
const DISTRIBUTION_MANIFEST_URLS = [
    joinUrl(R2_DISTRIBUTION_BASE_URL, 'manifests/production.json'),
    DISTRIBUTION_MANIFEST_URL,
    joinUrl(GITHUB_DISTRIBUTION_BASE_URL, 'manifests/production.json')
];
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

function joinUrl(baseUrl, relativePath) {
    return `${baseUrl.replace(/\/+$/, '')}/${relativePath.replace(/\\/g, '/').replace(/^\/+/, '')}`;
}

function googleDriveArtifactUrl(distribution, distributionPath, descriptor = {}) {
    const mirror = distribution && distribution.mirrors && distribution.mirrors.googleDrive;
    const files = mirror && Array.isArray(mirror.files) ? mirror.files : [];
    const normalizedPath = String(distributionPath || '').replace(/\\/g, '/').replace(/^\/+/, '');
    const expectedSize = Number(descriptor.size || 0);
    const expectedSha256 = String(descriptor.sha256 || '').trim().toLowerCase();
    const file = files.find(entry => entry && entry.path === normalizedPath
        && (!expectedSize || Number(entry.size) === expectedSize)
        && (!expectedSha256 || String(entry.sha256 || '').trim().toLowerCase() === expectedSha256));
    if (!file || typeof file.webContentLink !== 'string') return null;
    try {
        const parsed = new URL(file.webContentLink);
        const validId = /^[A-Za-z0-9_-]{10,}$/.test(String(parsed.searchParams.get('id') || ''));
        return parsed.protocol === 'https:' && parsed.hostname === 'drive.google.com'
            && parsed.pathname === '/uc' && validId ? parsed.toString() : null;
    } catch (_) {
        return null;
    }
}

// Distribution artifacts keep stable filenames between releases, while the
// CDN deliberately treats JARs as immutable. Keep the descriptor digest on
// every mirror candidate (not just the primary URL from the manifest), so a
// fallback can never be served an older cached artifact under the same name.
function versionedArtifactUrl(url, descriptor = {}) {
    const sha256 = String(descriptor.sha256 || '').trim().toLowerCase();
    if (!/^[a-f0-9]{64}$/.test(sha256)) return url;
    try {
        const parsed = new URL(url);
        parsed.searchParams.set('sha256', sha256);
        return parsed.toString();
    } catch (_) {
        return url;
    }
}

function distributionArtifactCandidates(descriptor, distributionPath, distribution) {
    const googleDriveUrl = googleDriveArtifactUrl(distribution, distributionPath, descriptor);
    return updateNetwork.uniqueUrls([
        descriptor.url,
        versionedArtifactUrl(joinUrl(R2_DISTRIBUTION_BASE_URL, distributionPath), descriptor),
        versionedArtifactUrl(joinUrl(DISTRIBUTION_BASE_URL, distributionPath), descriptor),
        versionedArtifactUrl(joinUrl(GITHUB_DISTRIBUTION_BASE_URL, distributionPath), descriptor),
        googleDriveUrl
    ]);
}

function downloadFileWithFallback(urls, dest, onProgress, timeoutMs = 20000, descriptor = {}) {
    return updateNetwork.downloadFileAdaptive({
        urls,
        dest,
        descriptor,
        onProgress,
        timeoutMs,
        probeBytes: 256 * 1024,
        probeTimeoutMs: 8000,
        rounds: 2,
        healthStore: mirrorHealthStore,
        log: logLauncher
    });
}

function requestJson(url, timeoutMs = 8000) {
    return updateNetwork.requestJson(url, timeoutMs);
}

function googleDriveMirrorUrl(distribution) {
    const candidate = distribution && distribution.mirrors && distribution.mirrors.googleDrive
        ? distribution.mirrors.googleDrive.folderUrl : null;
    if (typeof candidate !== 'string') return null;
    try {
        const parsed = new URL(candidate);
        return parsed.protocol === 'https:' && parsed.hostname === 'drive.google.com'
            && parsed.pathname.startsWith('/drive/folders/') ? parsed.toString() : null;
    } catch (_) {
        return null;
    }
}

function releaseForUi(release, distribution) {
    if (!release || typeof release !== 'object') return release;
    return { ...release, googleDriveMirrorAvailable: Boolean(googleDriveMirrorUrl(distribution)) };
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
const DISTRIBUTION_CACHE_PATH = path.join(app.getPath('userData'), 'distribution-cache.json');
const MIRROR_HEALTH_CACHE_PATH = path.join(app.getPath('userData'), 'mirror-health.json');
const mirrorHealthStore = new updateNetwork.MirrorHealthStore({
    cachePath: MIRROR_HEALTH_CACHE_PATH,
    log: logLauncher
});

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
    const result = await updateNetwork.fetchDistributionManifest({
        urls: DISTRIBUTION_MANIFEST_URLS,
        cachePath: DISTRIBUTION_CACHE_PATH,
        timeoutMs: 10000,
        log: logLauncher
    });
    logLauncher(`Loaded Eclipse distribution manifest from ${result.source}${result.cached ? ' (last-known-good cache)' : ''}`);
    return result.manifest;
}

async function fetchClientDistributionFromSources() {
    try {
        const distribution = await fetchDistributionManifest();
        return {
            mods: distribution.client.mods,
            profile: distribution.client.profile || null,
            distribution
        };
    } catch (error) {
        logLauncher(`Authoritative distribution manifest unavailable: ${error.message}`);
    }

    try {
        const localList = readLocalClientManifest();
        if (Array.isArray(localList)) {
            logLauncher(`Loaded client manifest from local source ${getLocalClientSource('mods.json')}`);
            return { mods: localList, profile: null };
        }
    } catch (error) {
        logLauncher(`Local client manifest unavailable: ${error.message}`);
    }

    throw new Error('No authoritative or cached client manifest is available. Refusing an unsafe downgrade.');
}

async function fetchRequiredModsFromSources() {
    const client = await fetchClientDistributionFromSources();
    return client.mods;
}

async function loadOptionalModsState() {
    const config = readConfig();
    let catalog;
    try {
        const manifest = await fetchRequiredModsFromSources();
        catalog = getOptionalMods(manifest);
        config.optionalModsCatalog = catalog;
        saveConfig(config);
    } catch (error) {
        catalog = Array.isArray(config.optionalModsCatalog) ? config.optionalModsCatalog : [];
        if (catalog.length === 0) throw error;
        logLauncher(`Using cached optional mod catalog: ${error.message}`);
    }

    return {
        catalog,
        mods: buildOptionalModViewFromCatalog(catalog, config.optionalMods)
    };
}

function catalogAsManifest(catalog) {
    return catalog.map(mod => ({
        optional: true,
        preferenceKey: mod.preferenceKey,
        modId: mod.modId,
        displayName: mod.name,
        version: mod.version,
        category: mod.category,
        description: mod.description,
        icon: mod.icon,
        defaultEnabled: mod.defaultEnabled
    }));
}

async function downloadClientAsset(relativePath, dest, onProgress, descriptor = {}, distribution = null) {
    const cleanPath = relativePath.replace(/\\/g, '/');
    const distributionPath = `client/${cleanPath}`;
    const candidates = distributionArtifactCandidates(descriptor, distributionPath, distribution);
    
    let lastError;
    try {
        await downloadFileWithFallback(candidates, dest, onProgress, 20000, descriptor);
        if ((descriptor.sha1 || descriptor.sha256 || descriptor.size) && !isManagedFileValid(dest, descriptor)) {
            throw new Error(`${relativePath} failed descriptor validation after download`);
        }
        logLauncher(`Downloaded ${relativePath} successfully`);
        return;
    } catch (error) {
        lastError = error;
    }

    const localSource = getLocalClientSource(relativePath);
    if (fs.existsSync(localSource)) {
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.copyFileSync(localSource, dest);
        if ((descriptor.sha1 || descriptor.sha256 || descriptor.size) && !isManagedFileValid(dest, descriptor)) {
            fs.unlinkSync(dest);
            throw new Error(`Local development source does not match the signed descriptor for ${relativePath}`);
        }
        logLauncher(`Copied ${relativePath} from local development source.`);
        return;
    }

    throw lastError || new Error(`No source available for ${relativePath}`);
}

async function repairManagedMod(mod, dest, onProgress, distribution = null) {
    const distributionPath = `client/${String(mod.path || '').replace(/\\/g, '/')}`;
    const candidates = distributionArtifactCandidates(mod, distributionPath, distribution);

    let lastError;
    try {
        const selectedUrl = await downloadFileWithFallback(candidates, dest, onProgress, 20000, mod);
        if (!isManagedFileValid(dest, mod)) throw new Error(`${mod.name} checksum mismatch after adaptive download`);
        logLauncher(`Downloaded and verified ${mod.name} from ${selectedUrl}`);
        return;
    } catch (error) {
        lastError = error;
        logLauncher(`Adaptive download failed for ${mod.name}: ${error.message}`);
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

ipcMain.on('get-optional-mods', async (event) => {
    try {
        const state = await loadOptionalModsState();
        event.reply('optional-mods-data', { success: true, mods: state.mods });
    } catch (error) {
        logLauncher(`Optional mod catalog unavailable: ${error.message}`);
        event.reply('optional-mods-data', { success: false, mods: [], error: error.message });
    }
});

ipcMain.on('set-optional-mod-enabled', async (event, payload) => {
    try {
        const state = await loadOptionalModsState();
        const preferenceKey = String(payload && payload.preferenceKey || '').trim().toLowerCase();
        const config = readConfig();
        config.optionalMods = setOptionalModPreference(
            config.optionalMods,
            preferenceKey,
            payload && payload.enabled === true,
            catalogAsManifest(state.catalog)
        );
        saveConfig(config);
        const mods = buildOptionalModViewFromCatalog(state.catalog, config.optionalMods);
        logLauncher(`Optional mod ${preferenceKey} is now ${payload && payload.enabled === true ? 'enabled' : 'disabled'}`);
        event.reply('optional-mods-data', { success: true, mods, changed: preferenceKey });
    } catch (error) {
        logLauncher(`Failed to save optional mod preference: ${error.message}`);
        event.reply('optional-mods-data', { success: false, error: error.message });
    }
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
        const launcherVersionDelta = updateNetwork.compareVersions(remoteLauncherVersion, currentLauncherVersion);
        if (remoteLauncherVersion && launcherVersionDelta > 0) {
            logLauncher(`Launcher update available: ${currentLauncherVersion} -> ${remoteLauncherVersion}`);
            event.reply('update-status', {
                updateRequired: true,
                isLauncherUpdate: true,
                release: {
                    title: 'Обновление лаунчера',
                    summary: `Доступна новая версия лаунчера ${remoteLauncherVersion} (текущая: ${currentLauncherVersion}). Для продолжения игры необходимо обновить лаунчер.`,
                    buttonLabel: 'ОБНОВИТЬ ЛАУНЧЕР',
                    googleDriveMirrorAvailable: Boolean(googleDriveMirrorUrl(distribution)),
                    notes: [
                        'Завершаются только процессы Eclipse RolePlay Launcher текущего пользователя',
                        'Minecraft, Java и другие приложения не затрагиваются',
                        'Перед запуском проверяются размер и SHA-256 установщика'
                    ]
                }
            });
            return;
        }
        if (remoteLauncherVersion && launcherVersionDelta < 0) {
            logLauncher(`Ignored launcher downgrade ${currentLauncherVersion} -> ${remoteLauncherVersion}`);
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
        event.reply('update-status', {
            updateRequired,
            release: updateRequired ? releaseForUi(release, distribution) : null
        });
    } catch (error) {
        logLauncher(`Release push check failed quietly: ${error.message}`);
        event.reply('update-status', { updateRequired: false });
    }
});

ipcMain.on('trigger-update', async (event, { gamePath }) => {
    const updateToken = updateOperationMutex.tryAcquire({
        progress: 0,
        message: 'Preparing update...'
    });
    if (!updateToken) {
        const activeUpdate = updateOperationMutex.snapshot();
        logLauncher(`Ignored concurrent update request; operation ${activeUpdate ? activeUpdate.id : 'unknown'} is already active.`);
        event.reply('update-progress', {
            status: 'busy',
            progress: activeUpdate && Number.isFinite(activeUpdate.progress) ? activeUpdate.progress : 0,
            message: activeUpdate && activeUpdate.message ? activeUpdate.message : 'Update is already running.',
            alreadyRunning: true
        });
        return;
    }

    const activeGamePath = gamePath || path.join(app.getPath('appData'), '.eclipse-rp');
    let activeRelease = null;
    let keepUpdateLockUntilExit = false;
    
    try {
        const distribution = await fetchDistributionManifest();
        
        // Handle launcher self-update download and execution
        const currentLauncherVersion = app.getVersion();
        const remoteLauncherVersion = distribution.launcher && distribution.launcher.version;
        const launcherVersionDelta = updateNetwork.compareVersions(remoteLauncherVersion, currentLauncherVersion);
        if (remoteLauncherVersion && launcherVersionDelta > 0) {
            const updateProgress = (message, progress) => {
                updateOperationMutex.update(updateToken, { message, progress });
                event.reply('update-progress', { status: 'downloading', progress, message });
            };
            
            updateProgress('Скачивание установщика лаунчера...', 10);
            const installerDest = path.join(app.getPath('temp'), `Eclipse-RolePlay-Launcher-Setup-${remoteLauncherVersion}.exe`);
            
            const installerCandidates = distributionArtifactCandidates(
                distribution.launcher,
                `launcher/stable/Eclipse-RolePlay-Launcher-Setup-${remoteLauncherVersion}.exe`,
                distribution
            );
            await downloadFileWithFallback(installerCandidates, installerDest, (received, total) => {
                const percent = Math.round((received / total) * 100);
                const mbReceived = (received / (1024 * 1024)).toFixed(1);
                const mbTotal = (total / (1024 * 1024)).toFixed(1);
                updateProgress(`Скачивание установщика лаунчера: ${percent}% [${mbReceived} MB / ${mbTotal} MB]...`, Math.round(10 + percent * 0.8));
            }, 20000, distribution.launcher);

            const installerSha256 = verifyDownloadedFile(installerDest, distribution.launcher);
            logLauncher(`Launcher setup verified: size=${fs.statSync(installerDest).size}, sha256=${installerSha256}`);
            
            updateProgress('Запуск установщика...', 95);
            const installerPid = await startDetachedInstaller(installerDest);
            logLauncher(`Detached launcher setup started: path=${installerDest}, pid=${installerPid}, args=/S --updated`);
            keepUpdateLockUntilExit = true;
            updateProgress('Установщик запущен. Лаунчер будет перезапущен после обновления.', 100);

            setTimeout(() => {
                logLauncher('Exiting old launcher so the detached installer can replace it.');
                app.exit(0);
            }, 300);
            return;
        }
        if (remoteLauncherVersion && launcherVersionDelta < 0) {
            logLauncher(`Ignored launcher downgrade ${currentLauncherVersion} -> ${remoteLauncherVersion}`);
        }

        let requiredMods;
        requiredMods = distribution.client.mods;
        activeRelease = releaseForUi(distribution.release || null, distribution);

        
        let totalSteps = 1 + requiredMods.length;
        let currentStep = 0;

        const updateProgress = (message, progress) => {
            updateOperationMutex.update(updateToken, { message, progress });
            event.reply('update-progress', { status: 'downloading', progress, message });
        };

        // 1. Download the Fabric 26.1.2 profile if missing or stale
        const versionJsonPath = getClientProfilePath(activeGamePath);
        if (!isClientProfileValid(versionJsonPath)) {
            updateProgress('Загрузка профиля запуска...', Math.round((currentStep / totalSteps) * 100));
            await downloadClientAsset(CLIENT_PROFILE_PATH, versionJsonPath, undefined, distribution.client.profile || {}, distribution);
        }
        currentStep++;

        // 2. Download or repair managed mods
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                updateProgress(`Загрузка мода: ${mod.name} (0%)...`, Math.round((currentStep / totalSteps) * 100));
                await repairManagedMod(mod, modLocalPath, (received, total) => {
                    const filePercent = Math.round((received / total) * 100);
                    const kbReceived = Math.round(received / 1024);
                    const kbTotal = Math.round(total / 1024);
                    const subProgress = Math.round(((currentStep + (received / total)) / totalSteps) * 100);
                    event.reply('update-progress', {
                        status: 'downloading',
                        progress: subProgress,
                        message: `Загрузка мода: ${mod.name} (${filePercent}%) [${kbReceived} KB / ${kbTotal} KB]...`
                    });
                }, distribution);
            }
            currentStep++;
        }

        // Commit the new managed set only after every replacement exists and
        // passes its integrity check. A transient mirror failure must never
        // delete the last working Eclipse client.
        removeObsoleteManagedMods(activeGamePath, requiredMods);

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
    } finally {
        if (!keepUpdateLockUntilExit && updateOperationMutex.release(updateToken)) {
            logLauncher(`Update operation ${updateToken.id} released.`);
        }
    }
});

ipcMain.on('open-google-drive-mirror', async (event) => {
    try {
        const distribution = await fetchDistributionManifest();
        const mirrorUrl = googleDriveMirrorUrl(distribution);
        if (!mirrorUrl) throw new Error('Зеркало Google Drive отсутствует для этого релиза.');
        await shell.openExternal(mirrorUrl);
        event.reply('google-drive-mirror-status', { success: true });
    } catch (error) {
        logLauncher(`Google Drive mirror could not be opened: ${error.message}`);
        event.reply('google-drive-mirror-status', { success: false, error: error.message });
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

        const clientDistribution = await fetchClientDistributionFromSources();
        if (!isClientProfileValid(versionJsonPath)) {
            await downloadClientAsset(CLIENT_PROFILE_PATH, versionJsonPath, undefined, clientDistribution.profile || {}, clientDistribution.distribution);
        }

        // Quick mod check
        const requiredMods = clientDistribution.mods;
        for (const mod of requiredMods) {
            const modLocalPath = path.join(activeGamePath, mod.path);
            if (!isManagedFileValid(modLocalPath, mod)) {
                await repairManagedMod(mod, modLocalPath, undefined, clientDistribution.distribution);
            }
        }
        removeObsoleteManagedMods(activeGamePath, requiredMods);

        const customJvmArgs = [`-Declipse.apiUrl=${normalizeApiUrl(apiUrl)}`];
        const fabricDisableArgument = buildFabricDisableArgument(requiredMods, config.optionalMods);
        if (fabricDisableArgument) {
            customJvmArgs.push(fabricDisableArgument);
            logLauncher(`Optional Fabric mods disabled for this launch: ${fabricDisableArgument.split('=')[1]}`);
        } else {
            logLauncher('All installed optional Fabric mods are enabled for this launch.');
        }

        const authSession = createOfflineAuthorization(username);
        logLauncher(`Prepared deterministic offline session uuid=${authSession.uuid}; Mojang User API disabled.`);

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
            customArgs: customJvmArgs,
            // The project intentionally authenticates inside the RP server. Tell
            // Minecraft to use UserApiService.OFFLINE instead of treating the
            // deterministic UUID as a Mojang access token and producing 401s.
            customLaunchArgs: [OFFLINE_DEVELOPER_ARGUMENT]
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
