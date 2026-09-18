const { contextBridge, ipcRenderer } = require('electron');

// The launcher renderer runs with contextIsolation on and Node integration off, so it can only
// reach the main process through this bridge. Only the channels the UI actually uses are exposed,
// which stops a compromised page (or a supply-chain script) from sending arbitrary IPC or touching
// the filesystem/child processes.
const SEND_CHANNELS = new Set([
    'check-updates',
    'get-config',
    'get-optional-mods',
    'get-server-status',
    'launch-game',
    'open-google-drive-mirror',
    'save-fullscreen',
    'save-username',
    'select-directory',
    'set-optional-mod-enabled',
    'trigger-update',
    'window-close',
    'window-minimize'
]);

const RECEIVE_CHANNELS = new Set([
    'config-data',
    'game-closed',
    'google-drive-mirror-status',
    'launch-status',
    'optional-mods-data',
    'selected-directory',
    'server-status-data',
    'update-progress',
    'update-status'
]);

contextBridge.exposeInMainWorld('ipcRenderer', {
    send: (channel, ...args) => {
        if (SEND_CHANNELS.has(channel)) {
            ipcRenderer.send(channel, ...args);
        }
    },
    on: (channel, listener) => {
        if (RECEIVE_CHANNELS.has(channel) && typeof listener === 'function') {
            ipcRenderer.on(channel, (event, ...args) => listener(event, ...args));
        }
    }
});
