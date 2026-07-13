'use strict';

const assert = require('assert');
const {
    getOptionalMods,
    buildOptionalModView,
    setOptionalModPreference,
    collectDisabledModIds,
    buildFabricDisableArgument
} = require('../optional-mods');

const manifest = [
    { name: 'required.jar' },
    {
        name: 'Axiom.jar', optional: true, preferenceKey: 'axiom', modId: 'axiom',
        displayName: 'Axiom', version: '5.4.2', defaultEnabled: true
    },
    {
        name: 'Camera.jar', optional: true, preferenceKey: 'camera_tools', modId: 'camera_tools',
        displayName: 'Camera Tools', defaultEnabled: false
    }
];

assert.strictEqual(getOptionalMods(manifest).length, 2);
assert.deepStrictEqual(
    buildOptionalModView(manifest, {}).map(mod => [mod.preferenceKey, mod.enabled]),
    [['axiom', true], ['camera_tools', false]]
);

const preferences = setOptionalModPreference({}, 'axiom', false, manifest);
assert.deepStrictEqual(preferences, { axiom: false });
assert.deepStrictEqual(collectDisabledModIds(manifest, preferences), ['axiom', 'camera_tools']);
assert.strictEqual(
    buildFabricDisableArgument(manifest, preferences),
    '-Dfabric.debug.disableModIds=axiom,camera_tools'
);
assert.strictEqual(buildFabricDisableArgument(manifest, { axiom: true, camera_tools: true }), null);
assert.throws(() => setOptionalModPreference({}, 'unknown', true, manifest), /Unknown optional mod preference/);

const malformed = [
    { optional: true, modId: '../bad', preferenceKey: 'bad id' },
    { optional: true, modId: 'valid_mod', preferenceKey: 'valid_mod' },
    { optional: true, modId: 'duplicate', preferenceKey: 'valid_mod' }
];
assert.deepStrictEqual(getOptionalMods(malformed).map(mod => mod.modId), ['valid_mod']);

console.log('optional-mods.test: manifest defaults, persistence and Fabric disable arguments passed');
