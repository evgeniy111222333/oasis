'use strict';

const MOD_ID_PATTERN = /^[a-z][a-z0-9_-]{1,63}$/;

function getOptionalMods(manifest) {
    if (!Array.isArray(manifest)) return [];

    const seenKeys = new Set();
    const result = [];
    for (const descriptor of manifest) {
        if (!descriptor || descriptor.optional !== true) continue;

        const modId = cleanId(descriptor.modId);
        const preferenceKey = cleanId(descriptor.preferenceKey || modId);
        if (!modId || !preferenceKey || seenKeys.has(preferenceKey)) continue;

        seenKeys.add(preferenceKey);
        result.push({
            preferenceKey,
            modId,
            name: cleanText(descriptor.displayName || descriptor.name, modId),
            version: cleanText(descriptor.version, ''),
            category: cleanText(descriptor.category, 'Дополнительно'),
            description: cleanText(descriptor.description, ''),
            icon: cleanIcon(descriptor.icon),
            defaultEnabled: descriptor.defaultEnabled !== false
        });
    }
    return result;
}

function buildOptionalModView(manifest, preferences) {
    return buildOptionalModViewFromCatalog(getOptionalMods(manifest), preferences);
}

function buildOptionalModViewFromCatalog(catalog, preferences) {
    const safePreferences = isPlainObject(preferences) ? preferences : {};
    const safeCatalog = Array.isArray(catalog) ? catalog : [];
    return safeCatalog.map(mod => ({
        ...mod,
        enabled: Object.prototype.hasOwnProperty.call(safePreferences, mod.preferenceKey)
            ? safePreferences[mod.preferenceKey] === true
            : mod.defaultEnabled
    }));
}

function setOptionalModPreference(preferences, preferenceKey, enabled, manifest) {
    const known = getOptionalMods(manifest).some(mod => mod.preferenceKey === preferenceKey);
    if (!known) throw new Error(`Unknown optional mod preference: ${preferenceKey}`);

    const next = isPlainObject(preferences) ? { ...preferences } : {};
    next[preferenceKey] = enabled === true;
    return next;
}

function collectDisabledModIds(manifest, preferences) {
    const ids = buildOptionalModView(manifest, preferences)
        .filter(mod => !mod.enabled)
        .map(mod => mod.modId);
    return [...new Set(ids)].sort();
}

function buildFabricDisableArgument(manifest, preferences) {
    const disabled = collectDisabledModIds(manifest, preferences);
    return disabled.length > 0 ? `-Dfabric.debug.disableModIds=${disabled.join(',')}` : null;
}

function cleanId(value) {
    const id = String(value || '').trim().toLowerCase();
    return MOD_ID_PATTERN.test(id) ? id : '';
}

function cleanText(value, fallback) {
    const text = String(value || '').trim();
    return text ? text.slice(0, 240) : fallback;
}

function cleanIcon(value) {
    const icon = String(value || '').trim();
    return /^fa-[a-z0-9-]{2,80}$/.test(icon) ? icon : 'fa-cube';
}

function isPlainObject(value) {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

module.exports = {
    getOptionalMods,
    buildOptionalModView,
    buildOptionalModViewFromCatalog,
    setOptionalModPreference,
    collectDisabledModIds,
    buildFabricDisableArgument
};
