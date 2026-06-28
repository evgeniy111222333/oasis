const params = new URLSearchParams(window.location.search);
const username = params.get("username") || "";
let selectedPart = "chest";
let lastData = null;
let currentSkinUuid = "";

document.getElementById("refreshBtn").addEventListener("click", loadVitals);
document.querySelectorAll("[data-part]").forEach((button) => {
    button.addEventListener("click", () => {
        selectedPart = button.dataset.part;
        render(lastData);
    });
});

async function loadVitals() {
    if (!username) return;
    try {
        const response = await fetch(`/api/vitals?username=${encodeURIComponent(username)}&ts=${Date.now()}`, { cache: "no-store" });
        const data = await response.json();
        if (!data.success) return;
        lastData = data;
        render(data);
    } catch (error) {
        // Keep the last known state visible.
    }
}

function render(data) {
    if (!data) return;
    const stamina = clamp(Number(data.stamina || 0), 0, 100);
    document.getElementById("staminaValue").textContent = `${Math.round(stamina)}%`;
    document.getElementById("staminaBar").style.width = `${stamina}%`;
    document.getElementById("staminaBand").textContent = data.bandLabel || "Стабільно";
    document.getElementById("breathDebt").textContent = `${Math.round(Number(data.breathDebt || 0))}%`;
    document.getElementById("fatigue").textContent = `${Math.round(Number(data.fatigue || 0))}%`;

    if (data.uuid && data.uuid !== currentSkinUuid) {
        currentSkinUuid = data.uuid;
        const skin = document.getElementById("skinImage");
        skin.src = `/api/appearance/texture?uuid=${encodeURIComponent(data.uuid)}`;
        skin.onerror = () => {
            skin.onerror = null;
            skin.src = `https://minotar.net/armor/body/${encodeURIComponent(username || "Steve")}/320.png`;
        };
    }

    const parts = Array.isArray(data.parts) ? data.parts : [];
    document.querySelectorAll("[data-part]").forEach((button) => {
        const part = parts.find((item) => item.id === button.dataset.part);
        button.classList.toggle("active", button.dataset.part === selectedPart);
        button.classList.toggle("injured", part && Number(part.condition) < 65);
        if (part) {
            button.textContent = `${part.label} · ${Math.round(part.condition)}%`;
        }
    });

    const selected = parts.find((part) => part.id === selectedPart) || parts[0];
    if (selected) {
        const card = document.getElementById("partCard");
        card.querySelector("h2").textContent = selected.label;
        card.querySelector("p").textContent = `${selected.state} · ${Math.round(selected.condition)}%`;
    }

    document.documentElement.style.setProperty("--breath-speed", stamina < 25 ? "1.1s" : stamina < 50 ? "1.55s" : "2.2s");
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

loadVitals();
setInterval(loadVitals, 1500);
