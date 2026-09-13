const params = new URLSearchParams(window.location.search);
const username = params.get("username") || "";
const partNames = {
    head: "Голова",
    chest: "Груди",
    leftArm: "Ліва рука",
    rightArm: "Права рука",
    leftLeg: "Ліва нога",
    rightLeg: "Права нога"
};

let selectedPart = "chest";
let activeView = "body";
let lastData = neutralVitals();

document.querySelectorAll("[data-part]").forEach((button) => {
    button.addEventListener("click", () => {
        selectedPart = button.dataset.part;
        render(lastData);
    });
});

document.querySelectorAll("[data-view]").forEach((button) => {
    button.addEventListener("click", () => {
        activeView = button.dataset.view;
        document.querySelectorAll("[data-view]").forEach((item) => item.classList.toggle("active", item === button));
        render(lastData);
    });
});

async function loadVitals() {
    if (!username) {
        render(lastData);
        return;
    }

    try {
        const response = await fetch(`/api/vitals?username=${encodeURIComponent(username)}&ts=${Date.now()}`, { cache: "no-store" });
        const data = await response.json();
        if (!data.success) return;
        lastData = data;
        render(data);
    } catch (error) {
        render(lastData);
    }
}

function render(data) {
    const stamina = clamp(Number(data.stamina ?? 100), 0, 100);
    const breathDebt = clamp(Number(data.breathDebt ?? 0), 0, 100);
    const fatigue = clamp(Number(data.fatigue ?? 0), 0, 100);
    const band = bandFromStamina(stamina);
    const profile = profileFor(stamina, breathDebt, fatigue);
    const danger = clamp(Math.max((42 - stamina) / 42, breathDebt / 140, fatigue / 150), 0, 1);

    const root = document.documentElement;
    root.style.setProperty("--danger", danger.toFixed(3));
    root.style.setProperty("--stamina", `${stamina}%`);
    root.style.setProperty("--debt", `${breathDebt}%`);
    root.style.setProperty("--fatigue", `${fatigue}%`);
    root.style.setProperty("--breath-speed", breathSpeed(stamina, breathDebt));

    const app = $("#app");
    app.className = `game-window ${band} view-${activeView}`;

    $("#bandChip").textContent = profile.chip;
    $("#staminaValue").textContent = `${Math.round(stamina)}%`;
    $("#staminaMetric").textContent = String(Math.round(stamina));
    $("#breathDebt").textContent = `${Math.round(breathDebt)}%`;
    $("#fatigue").textContent = `${Math.round(fatigue)}%`;
    $("#coreTitle").textContent = profile.title;
    $("#coreAdvice").textContent = profile.advice;
    $("#breathLabel").textContent = profile.breathLabel;
    $("#breathText").textContent = profile.breathText;
    $("#gameplayLabel").textContent = profile.gameplayLabel;
    $("#gameplayText").textContent = profile.gameplayText;

    setCircle("#staminaCircle", 302, stamina);
    setCircle("#debtCircle", 239, breathDebt);
    setVitalBars(stamina, breathDebt, fatigue);
    updateBody(data, stamina, breathDebt, fatigue);
    updateWarnings(profile.warnings);
}

function updateBody(data, stamina, breathDebt, fatigue) {
    const parts = Array.isArray(data.parts) ? data.parts : [];

    document.querySelectorAll("[data-part]").forEach((button) => {
        const id = button.dataset.part;
        const part = parts.find((item) => item.id === id);
        const condition = part ? clamp(Number(part.condition ?? 100), 0, 100) : inferredPartCondition(id, stamina, breathDebt, fatigue);
        button.classList.toggle("active", id === selectedPart);
        button.classList.toggle("warn", condition < 76 && condition >= 46);
        button.classList.toggle("bad", condition < 46);
        button.title = `${partNames[id] || id}: ${stateFromCondition(condition)} · ${Math.round(condition)}%`;
    });

    const selected = parts.find((part) => part.id === selectedPart);
    const condition = selected ? clamp(Number(selected.condition ?? 100), 0, 100) : inferredPartCondition(selectedPart, stamina, breathDebt, fatigue);
    $("#partTitle").textContent = partNames[selectedPart] || selected?.label || "Зона";
    $("#partState").textContent = `${stateFromCondition(condition)} · ${Math.round(condition)}%`;
}

function setVitalBars(stamina, breathDebt, fatigue) {
    const bars = document.querySelectorAll(".vital");
    if (bars[0]) setVital(bars[0], breathDebt, scoreColor(100 - breathDebt));
    if (bars[1]) setVital(bars[1], fatigue, scoreColor(100 - fatigue));
    if (bars[2]) setVital(bars[2], Math.max(10, 100 - stamina + breathDebt * 0.35), scoreColor(stamina));
}

function setVital(node, value, color) {
    node.style.setProperty("--vital-fill", `${clamp(value, 0, 100)}%`);
    node.style.setProperty("--vital-color", color);
}

function updateWarnings(warnings) {
    const list = $("#warningList");
    list.innerHTML = "";
    warnings.slice(0, 2).forEach((warning) => {
        const item = document.createElement("li");
        item.textContent = warning;
        list.appendChild(item);
    });
}

function setCircle(selector, length, value) {
    const circle = document.querySelector(selector);
    if (!circle) return;
    circle.style.strokeDashoffset = String(length - length * clamp(value, 0, 100) / 100);
}

function profileFor(stamina, breathDebt, fatigue) {
    const warnings = [];
    if (stamina < 5) warnings.push("Спринт майже одразу зривається.");
    else if (stamina < 25) warnings.push("Рух стає важким, відновлення повільніше.");
    else if (stamina < 50) warnings.push("Після ривків потрібна коротка пауза.");
    if (breathDebt > 60) warnings.push("Дихання збите, плечі працюють частіше.");
    if (fatigue > 55) warnings.push("Втома знижує темп і точність.");
    if (!warnings.length) warnings.push("Стан контрольований.");

    if (stamina < 5) {
        return {
            chip: "Виснаження",
            title: "Треба віддихатись",
            advice: "Зір стискається, дихання рване, тіло просить паузу.",
            breathLabel: "Рване",
            breathText: "Короткі видихи, важкий вдих, плечі піднімаються частіше.",
            gameplayLabel: "Пауза",
            gameplayText: "Спринт блокується або швидко рветься. Краще зупинитись.",
            warnings
        };
    }
    if (stamina < 25) {
        return {
            chip: "На межі",
            title: "Важке дихання",
            advice: "Кожен ривок коштує дорого. Рухи вже не такі пружні.",
            breathLabel: "Глибоке",
            breathText: "Видно сильніший вдих через груди й плечі, без розвалу скелета.",
            gameplayLabel: "Обмеження",
            gameplayText: "Спринт і стрибки краще робити короткими серіями.",
            warnings
        };
    }
    if (stamina < 50) {
        return {
            chip: "Втома",
            title: "Темп просідає",
            advice: "Персонаж ще рухається нормально, але дихання вже частіше.",
            breathLabel: "Частіше",
            breathText: "Плечі активніші, руки трохи нижче, корпус зібраний.",
            gameplayLabel: "Помірно",
            gameplayText: "Чергуй біг і ходу, щоб не накопичити борг.",
            warnings
        };
    }
    if (stamina < 80) {
        return {
            chip: "Розігрів",
            title: "Тіло в роботі",
            advice: "Після руху дихання живіше, але запас ще комфортний.",
            breathLabel: "Активне",
            breathText: "Легка живість через груди, плечі й руки.",
            gameplayLabel: "Нормально",
            gameplayText: "Ривок доступний, довгий спринт поступово накопичує борг.",
            warnings
        };
    }
    return {
        chip: "Стабільно",
        title: "Рівне дихання",
        advice: "Тіло тримає темп без перенапруги.",
        breathLabel: "Рівний",
        breathText: "Плечі працюють рівно, без видимого перенапруження.",
        gameplayLabel: "Без штрафів",
        gameplayText: "Спринт, стрибки та атаки доступні у звичному темпі.",
        warnings
    };
}

function inferredPartCondition(id, stamina, breathDebt, fatigue) {
    if (id === "chest") return clamp(100 - breathDebt * 0.30, 0, 100);
    if (id === "leftLeg" || id === "rightLeg") return clamp(100 - (100 - stamina) * 0.30, 0, 100);
    if (id === "leftArm" || id === "rightArm") return clamp(100 - fatigue * 0.22, 0, 100);
    return clamp(100 - fatigue * 0.12, 0, 100);
}

function stateFromCondition(condition) {
    if (condition < 35) return "Травма";
    if (condition < 65) return "Перевтома";
    if (condition < 88) return "Напруга";
    return "Стабільно";
}

function bandFromStamina(stamina) {
    if (stamina >= 80) return "steady";
    if (stamina >= 50) return "warmed";
    if (stamina >= 25) return "tired";
    if (stamina >= 5) return "strained";
    return "exhausted";
}

function breathSpeed(stamina, breathDebt) {
    if (stamina < 5 || breathDebt > 75) return "0.82s";
    if (stamina < 25 || breathDebt > 55) return "1.06s";
    if (stamina < 50 || breathDebt > 32) return "1.46s";
    if (stamina < 80) return "1.95s";
    return "2.45s";
}

function scoreColor(score) {
    if (score < 35) return "#e47e70";
    if (score < 62) return "#ddb87e";
    return "#9fc8bd";
}

function neutralVitals() {
    return {
        success: true,
        stamina: 100,
        breathDebt: 0,
        fatigue: 0,
        parts: Object.keys(partNames).map((id) => ({ id, condition: 100 }))
    };
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function $(selector) {
    return document.querySelector(selector);
}

render(lastData);
loadVitals();
setInterval(loadVitals, 900);
