const assert = require('assert');
const path = require('path');
const { app, BrowserWindow } = require('electron');

async function measureUpdateModal(win, noteCount, showProgress, showMirror) {
    return win.webContents.executeJavaScript(`
        (() => {
            const modal = document.getElementById('updateModal');
            const title = document.getElementById('updateTitle');
            const summary = document.getElementById('updateSummary');
            const notes = document.getElementById('updateNotes');
            const progress = document.getElementById('modalProgressContainer');
            const mirror = document.getElementById('btnGoogleDriveMirror');
            title.textContent = 'Проверка обновления лаунчера';
            summary.textContent = 'Проверяем, что весь дизайн, список изменений, прогресс и кнопки остаются внутри окна.';
            notes.replaceChildren();
            for (let index = 0; index < ${noteCount}; index += 1) {
                const row = document.createElement('div');
                row.className = 'file-item';
                row.innerHTML = '<i class="fa-solid fa-circle-check"></i><span>Длинный пункт обновления № ' + index + ': подробное описание изменения интерфейса и поведения лаунчера.</span>';
                notes.appendChild(row);
            }
            progress.style.display = ${showProgress ? "'flex'" : "'none'"};
            mirror.hidden = ${showMirror ? 'false' : 'true'};
            modal.style.display = 'flex';
            notes.scrollTop = 0;
            return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => {
                const rect = element => {
                    const value = element.getBoundingClientRect();
                    return { top: value.top, right: value.right, bottom: value.bottom, left: value.left, width: value.width, height: value.height };
                };
                resolve({
                    viewport: { width: innerWidth, height: innerHeight },
                    modal: rect(document.querySelector('.update-content')),
                    header: rect(document.querySelector('.update-header')),
                    notes: rect(notes),
                    footer: rect(document.querySelector('.update-footer')),
                    action: rect(document.getElementById('btnStartUpdate')),
                    mirror: rect(mirror),
                    progress: rect(progress),
                    scrollHeight: notes.scrollHeight,
                    clientHeight: notes.clientHeight
                });
            })));
        })()
    `);
}

function assertInsideViewport(name, rect, viewport) {
    assert(rect.top >= 0, `${name} starts above the viewport`);
    assert(rect.left >= 0, `${name} starts left of the viewport`);
    assert(rect.right <= viewport.width, `${name} ends right of the viewport`);
    assert(rect.bottom <= viewport.height, `${name} ends below the viewport`);
}

app.whenReady().then(async () => {
    const win = new BrowserWindow({
        width: 950,
        height: 580,
        show: false,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false
        }
    });

    try {
        await win.loadFile(path.join(__dirname, '..', 'index.html'));

        const longLayout = await measureUpdateModal(win, 24, true, true);
        assertInsideViewport('modal', longLayout.modal, longLayout.viewport);
        assertInsideViewport('header', longLayout.header, longLayout.viewport);
        assertInsideViewport('notes', longLayout.notes, longLayout.viewport);
        assertInsideViewport('footer', longLayout.footer, longLayout.viewport);
        assertInsideViewport('primary action', longLayout.action, longLayout.viewport);
        assertInsideViewport('mirror action', longLayout.mirror, longLayout.viewport);
        assertInsideViewport('progress', longLayout.progress, longLayout.viewport);
        assert(longLayout.scrollHeight > longLayout.clientHeight, 'long notes must scroll inside their own region');
        assert(longLayout.footer.top >= longLayout.notes.bottom, 'footer must remain below the notes region');

        const shortLayout = await measureUpdateModal(win, 2, false, false);
        assertInsideViewport('short modal', shortLayout.modal, shortLayout.viewport);
        assertInsideViewport('short footer', shortLayout.footer, shortLayout.viewport);
        assert(shortLayout.scrollHeight <= shortLayout.clientHeight + 1, 'short notes must not receive unnecessary scrolling');

        console.log('Update modal layout checks passed.');
    } finally {
        win.destroy();
        app.quit();
    }
}).catch(error => {
    console.error(error);
    app.exit(1);
});
