'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');

assert(!source.includes('/api/required-mods'), 'stale server API must not be an authoritative manifest fallback');
assert(source.includes('distribution-cache.json'), 'last-known-good distribution cache is required');
assert(source.includes('R2_DISTRIBUTION_BASE_URL'), 'R2 mirror is required');
assert(source.includes('GITHUB_DISTRIBUTION_BASE_URL'), 'GitHub mirror is required');
assert(source.includes('mirror-health.json'), 'temporary network-scoped mirror health cache is required');
assert(source.includes('downloadFileAdaptive'), 'all managed artifacts must use adaptive mirror selection');
assert(source.includes('distribution.client.profile || {}'), 'the client profile download must receive its integrity descriptor');
assert(source.includes('}, 20000, distribution.launcher);'), 'the launcher installer download must receive its SHA-256 descriptor');
assert(source.includes('updateOperationMutex.tryAcquire'), 'main-process update mutex is required');
assert(source.includes('Ignored concurrent update request'), 'parallel update requests must be rejected and logged');
assert(source.includes('keepUpdateLockUntilExit = true'), 'self-update mutex must remain held until the old launcher exits');
assert.strictEqual((source.match(/launcherVersionDelta > 0/g) || []).length, 2, 'both update paths must reject launcher downgrades');

const updateSection = source.slice(
    source.indexOf("ipcMain.on('trigger-update'"),
    source.indexOf('// Choose folder')
);
const launchSection = source.slice(source.indexOf("ipcMain.on('launch-game'"));
for (const [name, section] of [['update', updateSection], ['launch', launchSection]]) {
    const repairIndex = section.lastIndexOf('await repairManagedMod(');
    const cleanupIndex = section.indexOf('removeObsoleteManagedMods(');
    assert(repairIndex >= 0 && cleanupIndex > repairIndex, `${name} deletes the previous client before replacements verify`);
}

console.log('launcher-update-safety.test: authoritative manifest and deferred cleanup invariants passed');
