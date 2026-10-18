const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');
const renderer = fs.readFileSync(path.join(__dirname, '..', 'renderer.js'), 'utf8');

assert(source.includes("parsed.hostname === 'drive.google.com'"),
    'Google Drive mirror URL must be host-validated in the main process');
assert(source.includes('function googleDriveArtifactUrl'),
    'launcher must resolve validated per-file Google Drive download URLs');
assert(source.includes('googleDriveUrl'),
    'Google Drive must participate in automatic artifact mirror selection');
assert(source.includes("parsed.pathname === '/uc'"),
    'automatic Google Drive URLs must be restricted to the direct-download endpoint');
assert(source.includes("ipcMain.on('open-google-drive-mirror'"),
    'launcher must expose a main-process-only Google Drive mirror action');
assert(source.includes('shell.openExternal(mirrorUrl)'),
    'validated mirror URL must open only through Electron shell');
assert(renderer.includes('showGoogleDriveMirrorIfAvailable'),
    'renderer must reveal the Google Drive action only after a failed primary update');

console.log('google-drive-mirror.test: validated manual and automatic Drive mirrors are wired');
