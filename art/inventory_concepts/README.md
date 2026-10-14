# Inventory concepts: survival and creative

Generated with the built-in imagegen tool on 2026-09-27. Each transparent PNG shows a paired survival window (left) and creative window (right). These are visual concepts, not final slot atlases: image generation does not guarantee exact slot counts or coordinates.

## Prompt set

1. **Eclipse Observatory** (`01_eclipse_observatory_survival_creative.png`): blackened iron, antique brass, an eclipse crest, violet stones and cold moonstone highlights, matching the selected TAB and HUD. Survival has a left player stand, armor/offhand sockets, a 2×2 crafting area, storage and hotbar. Creative has the same stand, category icons, search, scroll track, catalog and hotbar. Empty dark slots, no text or baked player model.
2. **Chronicler's Folio** (`02_chroniclers_folio_survival_creative.png`): traveling medieval inventory in walnut, aged parchment, brass, burgundy seals and blue lamplight, with the same two-mode functional layout and a lit player stand on the left of both windows.
3. **Moonlit Reliquary** (`03_moonlit_reliquary_survival_creative.png`): a compact, restrained alternative with charcoal steel, fine silver, violet and cyan accents; both modes reserve a left player alcove and keep catalog/item wells visually clear.

## Integration plan for the selected direction

- Build the final backgrounds as sliced GUI textures, then position real Minecraft slots over them. The survival screen must retain the actual 3×9 storage slots, 9 hotbar slots, 2×2 crafting/output, armor and offhand slots. The creative screen must retain its real category tabs, searchable item catalog, scrollbar, hotbar and permissions.
- Render the live 3D player model in the empty left alcove in both modes. Keep the active skin, clothes and equipment. Add drag-to-rotate only if it does not interfere with inventory item dragging.
- Keep item icons, counts, tooltips, search results and drag/drop behavior from Minecraft; the generated wells are only the visual frame. Draw any Russian labels in code, never as baked image text.
- The selected concept needs a final grid aligned to Minecraft's actual slot coordinates at each GUI scale. Do not use the illustrative generated cells as hit boxes.
