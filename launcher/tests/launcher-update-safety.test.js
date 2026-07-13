'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');

assert(!source.includes('/api/required-mods'), 'stale server API must not be an authoritative manifest fallback');
assert(source.includes('distribution-cache.json'), 'last-known-good distribution cache is required');
assert(source.includes('R2_DISTRIBUTION_BASE_URL'), 'R2 mirror is required');
assert(source.includes('GITHUB_DISTRIBUTION_BASE_URL'), 'GitHub mirror is required');
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
