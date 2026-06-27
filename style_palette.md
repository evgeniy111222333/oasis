# 🌾 Aether Minimalist — Desert Oasis Style Palette

This document registers the color codes and formatting details for the **Aether Minimalist** style, utilizing the **Desert Oasis** palette.

---

## 🎨 Color Palette Reference

| Chat Element | Color Token | Hex Code | Visual Sample / Vibe |
| :--- | :--- | :--- | :--- |
| **Player Name (IC)** | `S1_NAME` | `#E3C099` | Luxury Sand / Champagne Gold |
| **Action Text (`/me`)** | `S1_ACTION` | `#C3C4A5` | Pale Pistachio / Muted Olive |
| **Environment (`/do`)** | `S1_DO` | `#A5C3C4` | Muted Seafoam / Slate Sage |
| **Separators / Verbs** | `S1_MUTED` | `#B0A8A0` | Pebble Gray |
| **Speech Text (IC)** | `NamedTextColor.WHITE` | `#FFFFFF` | Pure White |
| **OOC Player Name** | `S1_OOC_NAME` | `#8A827A` | Dry Earth Gray |
| **OOC Message Text** | `S1_OOC_MSG` | `#AFA69E` | Warm Dust Gray |
| **OOC Separator (`:`)** | `S1_OOC_SEP` | `#B0A8A0` | Pebble Gray |
| **Attempt Success** | `COLOR_SUCCESS` | `#99C3A2` | Soft Olive Green |
| **Attempt Failure** | `COLOR_FAIL` | `#E3A899` | Soft Terracotta Red |

---

## ✍️ Chat Format Examples

### 1. IC Chat (Local Speech)
* **Format:** `Name каже: «Message»`
* **Syntax:** `[Name in #E3C099] [каже: in #B0A8A0] [«Message» in #FFFFFF]`
* **Example:** `zVinka каже: «Привіт усім, як ваші справи?»`

### 2. Character Action (`/me`)
* **Format:** `Name action`
* **Syntax:** `[Name in #E3C099] [action in #C3C4A5]`
* **Example:** `zVinka дістав телефон з кишені штанів`

### 3. Environment Description (`/do`)
* **Format:** `description — Name`
* **Syntax:** `[description in #A5C3C4] [ — in #B0A8A0] [Name in #B0A8A0]`
* **Example:** `Телефон знаходиться у правій руці — zVinka`

### 4. Attempt Command (`/try`)
* **Format:** `Name намагається action... [Успішно]` or `[Неуспішно]`
* **Syntax:** `[Name in #E3C099] [намагається action... in #C3C4A5] [[Успішно] in #99C3A2 / [Неуспішно] in #E3A899]`
* **Example:** `zVinka намагається завести двигун... [Успішно]`

### 5. Speech with Action (`/todo`)
* **Format:** `«speech» — Name, action`
* **Syntax:** `[«speech» in #FFFFFF] [ — in #B0A8A0] [Name in #E3C099] [, action in #C3C4A5]`
* **Example:** `«Зачекайте одну хвилину» — zVinka, шукаючи ключі в кишені`

### 6. OOC Local Chat (`/b`)
* **Format:** `Name: message`
* **Syntax:** `[Name in #8A827A] [: in #B0A8A0] [message in #AFA69E]`
* **Example:** `zVinka: привіт, це OOC повідомлення`
