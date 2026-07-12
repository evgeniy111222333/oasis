import { useState, useEffect, useCallback } from 'react';
import CharacterBody from './components/CharacterBody';
import type { BodyPart } from './components/CharacterBody';
import StaminaPanel from './components/StaminaPanel';
import type { StatusMetrics } from './components/StaminaPanel';
import StatusDescription from './components/StatusDescription';
import './index.css';

type VitalsPart = {
  id: string;
  label?: string;
  condition?: number;
  state?: string;
  injury?: string;
  lastCause?: string;
  bleeding?: number;
  pain?: number;
  fracture?: boolean;
  burn?: number;
  infection?: boolean;
  embeddedArrow?: boolean;
  openWound?: boolean;
  bandaged?: boolean;
  woundCleaned?: boolean;
  fractureStabilized?: boolean;
  tourniquet?: boolean;
  medicated?: boolean;
  antibiotics?: boolean;
  burnTreated?: boolean;
  actions?: TreatmentOption[];
};

type TreatmentStatus = {
  active?: boolean;
  partId?: string;
  partLabel?: string;
  action?: string;
  label?: string;
  progress?: number;
  remainingTicks?: number;
  totalTicks?: number;
};

type VitalsResponse = {
  success?: boolean;
  player?: string;
  rpName?: string;
  stamina?: number;
  breathDebt?: number;
  fatigue?: number;
  blood?: number;
  pain?: number;
  bleeding?: number;
  unconscious?: boolean;
  treatment?: TreatmentStatus;
  parts?: VitalsPart[];
};

type BodyStatuses = {
  head: string;
  torso: string;
  leftArm: string;
  rightArm: string;
  leftLeg: string;
  rightLeg: string;
};

export type BodyPartDetails = Record<Exclude<BodyPart, null>, VitalsPart>;

const params = new URLSearchParams(window.location.search);
const username = params.get('username') || '';
const apiUrl = params.get('apiUrl') || 'https://api.eclipse-roleplay.online';
const NORMAL = 'Нормально';

const EMPTY_PARTS: BodyPartDetails = {
  head: { id: 'head', label: 'Голова', condition: 100, state: NORMAL },
  torso: { id: 'chest', label: 'Торс', condition: 100, state: NORMAL },
  leftArm: { id: 'leftArm', label: 'Левая рука', condition: 100, state: NORMAL },
  rightArm: { id: 'rightArm', label: 'Правая рука', condition: 100, state: NORMAL },
  leftLeg: { id: 'leftLeg', label: 'Левая нога', condition: 100, state: NORMAL },
  rightLeg: { id: 'rightLeg', label: 'Правая нога', condition: 100, state: NORMAL },
};

const DEFAULT_STATUSES: BodyStatuses = {
  head: NORMAL,
  torso: NORMAL,
  leftArm: NORMAL,
  rightArm: NORMAL,
  leftLeg: NORMAL,
  rightLeg: NORMAL,
};

function App() {
  const [activePart, setActivePart] = useState<BodyPart>(null);
  const [hoveredPart, setHoveredPart] = useState<BodyPart>(null);
  const [hoveredMetric, setHoveredMetric] = useState<string | null>(null);
  const [menuVisible, setMenuVisible] = useState(false);
  const [characterName, setCharacterName] = useState(username || 'Vigor');
  const [bodyStatuses, setBodyStatuses] = useState<BodyStatuses>(DEFAULT_STATUSES);
  const [bodyParts, setBodyParts] = useState<BodyPartDetails>(EMPTY_PARTS);
  const [treatment, setTreatment] = useState<TreatmentStatus>({});
  const [treatmentNotice, setTreatmentNotice] = useState('');
  const [metrics, setMetrics] = useState<StatusMetrics>({
    stamina: 100,
    breathDebt: 0,
    tension: 0,
    blood: 100,
    pain: 0,
    bleeding: 0,
    unconscious: false,
  });

  useEffect(() => {
    const timer = setTimeout(() => setMenuVisible(true), 80);
    return () => clearTimeout(timer);
  }, []);

  useEffect(() => {
    const preventBrowserFeel = (event: Event) => event.preventDefault();
    document.addEventListener('selectstart', preventBrowserFeel);
    document.addEventListener('dragstart', preventBrowserFeel);
    document.addEventListener('contextmenu', preventBrowserFeel);
    return () => {
      document.removeEventListener('selectstart', preventBrowserFeel);
      document.removeEventListener('dragstart', preventBrowserFeel);
      document.removeEventListener('contextmenu', preventBrowserFeel);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;

    const loadVitals = async () => {
      if (!username) return;
      try {
        const response = await fetch(`${apiUrl}/api/vitals?username=${encodeURIComponent(username)}&ts=${Date.now()}`, {
          cache: 'no-store',
        });
        if (!response.ok) return;
        const data = (await response.json()) as VitalsResponse;
        if (!data.success || cancelled) return;

        const mappedParts = mapBodyParts(data.parts || []);
        setCharacterName(data.rpName || data.player || username || 'Vigor');
        setMetrics({
          stamina: clamp(Number(data.stamina ?? 100), 0, 100),
          breathDebt: clamp(Number(data.breathDebt ?? 0), 0, 100),
          tension: clamp(Number(data.fatigue ?? 0), 0, 100),
          blood: clamp(Number(data.blood ?? 100), 0, 100),
          pain: clamp(Number(data.pain ?? 0), 0, 100),
          bleeding: clamp(Number(data.bleeding ?? 0), 0, 100),
          unconscious: Boolean(data.unconscious),
        });
        setBodyParts(mappedParts);
        setBodyStatuses(mapBodyStatuses(mappedParts));
        setTreatment(data.treatment || {});
      } catch {
        // Keep the last known state visible inside the in-game browser.
      }
    };

    loadVitals();
    const interval = window.setInterval(loadVitals, 900);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, []);

  const handlePartHover = useCallback((part: BodyPart) => {
    setHoveredPart(part);
  }, []);

  const handlePartClick = useCallback((part: BodyPart) => {
    setActivePart(prev => prev === part ? null : part);
  }, []);

  const handleMetricHover = useCallback((metric: string | null) => {
    setHoveredMetric(metric);
  }, []);

  const handleTreat = useCallback(async (action: string) => {
    if (!username || !activePart) return;
    const part = bodyParts[activePart];
    if (!part?.id) return;

    setTreatmentNotice('');
    try {
      const response = await fetch(`${apiUrl}/api/vitals/treat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, partId: part.id, action }),
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || !data.success) {
        setTreatmentNotice(data.message || 'Не удалось начать действие');
        return;
      }
      setTreatment(data.treatment || {});
      setTreatmentNotice(data.message || 'Лечение начато');
    } catch {
      setTreatmentNotice('Нет связи с медицинской системой');
    }
  }, [activePart, bodyParts]);

  return (
    <div className="relative w-screen h-screen overflow-hidden bg-transparent">
      {menuVisible && (
        <div className="relative z-10 flex items-center justify-center w-full h-full p-3">
          <div className="flex items-center justify-center gap-8 max-w-[1040px] w-full menu-enter">
            <div className="flex-shrink-0">
              <div className="relative">
                <div className="corner-ornament tl" />
                <div className="corner-ornament tr" />
                <div className="corner-ornament bl" />
                <div className="corner-ornament br" />

                <div className="glass-panel rounded-xl p-5">
                  <div className="text-center mb-2">
                    <h2 className="font-cinzel text-base tracking-[0.2em] text-[#E8DCC4]">
                      СОСТОЯНИЕ ТЕЛА
                    </h2>
                    <div className="ornament-line mt-2 mx-auto w-24" />
                  </div>

                  <CharacterBody
                    onPartHover={handlePartHover}
                    onPartClick={handlePartClick}
                    activePart={activePart}
                    headStatus={bodyStatuses.head}
                    torsoStatus={bodyStatuses.torso}
                    leftArmStatus={bodyStatuses.leftArm}
                    rightArmStatus={bodyStatuses.rightArm}
                    leftLegStatus={bodyStatuses.leftLeg}
                    rightLegStatus={bodyStatuses.rightLeg}
                  />

                  <div className="flex items-center justify-center gap-3 mt-2">
                    <div className="flex items-center gap-1.5">
                      <div className="w-2 h-2 rounded-full bg-[#7BC67E]" />
                      <span className="font-inter text-[9px] text-[#CCCCCC]">Норма</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <div className="w-2 h-2 rounded-full bg-[#E8A55B]" />
                      <span className="font-inter text-[9px] text-[#CCCCCC]">Травма</span>
                    </div>
                    <div className="flex items-center gap-1.5">
                      <div className="w-2 h-2 rounded-full bg-[#E85D5D]" />
                      <span className="font-inter text-[9px] text-[#CCCCCC]">Критично</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div className="flex flex-col gap-3 w-[360px] max-h-[calc(100vh-24px)] overflow-y-auto pr-1 slide-in-right">
              <div className="text-center">
                <h1 className="font-cinzel text-2xl tracking-[0.15em] text-[#E8DCC4] truncate">
                  {characterName}
                </h1>
                <p className="font-inter text-xs text-[#CCCCCC] opacity-60 tracking-wider mt-1">
                  СТАТУС ПЕРСОНАЖА
                </p>
                <div className="ornament-line mt-2" />
              </div>

              <StaminaPanel
                metrics={metrics}
                onMetricHover={handleMetricHover}
              />

              <StatusDescription
                activePart={hoveredPart || activePart}
                parts={bodyParts}
                hoveredMetric={hoveredMetric}
                metrics={metrics}
              />

              <TreatmentPanel
                activePart={activePart}
                parts={bodyParts}
                treatment={treatment}
                notice={treatmentNotice}
                onTreat={handleTreat}
              />

              <div className="text-center opacity-40">
                <p className="font-inter text-[11px] text-[#CCCCCC] tracking-wider">
                  НАВЕДИТЕ НА ЗОНУ ИЛИ ПОКАЗАТЕЛЬ ДЛЯ ДЕТАЛЕЙ
                </p>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

type TreatmentPanelProps = {
  activePart: BodyPart;
  parts: BodyPartDetails;
  treatment: TreatmentStatus;
  notice: string;
  onTreat: (action: string) => void;
};

type TreatmentOption = {
  id: string;
  label: string;
  item: string;
  duration: string;
  description: string;
  enabled?: boolean;
  reason?: string;
  durationTicks?: number;
};

function TreatmentPanel({ activePart, parts, treatment, notice, onTreat }: TreatmentPanelProps) {
  if (!activePart) {
    return (
      <div className="glass-panel rounded-lg p-3">
        <h3 className="font-cinzel text-sm tracking-wider text-[#E8DCC4]">Медицинское действие</h3>
        <div className="ornament-line my-2" />
        <p className="font-inter text-xs text-[#CCCCCC] leading-relaxed">
          Выберите часть тела, чтобы увидеть доступное лечение.
        </p>
      </div>
    );
  }

  const part = parts[activePart];
  const options = treatmentOptions(activePart, part);
  const activeTreatment = Boolean(treatment?.active);
  const currentPartTreatment = activeTreatment && treatment.partId === part.id;

  return (
    <div className="glass-panel rounded-lg p-3 fade-in">
      <div className="flex items-center justify-between gap-3">
        <h3 className="font-cinzel text-sm tracking-wider text-[#E8DCC4]">Лечение</h3>
        <span className="font-inter text-[10px] text-[#CCCCCC] opacity-70">
          {partLabel(activePart)}
        </span>
      </div>
      <div className="ornament-line my-2" />

      {activeTreatment && (
        <div className="mb-3 rounded border border-[rgba(232,220,196,0.18)] bg-[rgba(232,220,196,0.06)] p-2">
          <div className="flex items-center justify-between gap-2 mb-1.5">
            <span className="font-inter text-[11px] text-[#E8DCC4] truncate">
              {treatment.label || 'Лечение'}
            </span>
            <span className="font-cinzel text-xs text-[#E8A55B]">
              {Math.round(treatment.progress ?? 0)}%
            </span>
          </div>
          <div className="h-1.5 rounded-full bg-[rgba(204,204,204,0.10)] overflow-hidden">
            <div
              className="h-full rounded-full transition-all duration-300"
              style={{
                width: `${Math.max(0, Math.min(100, treatment.progress ?? 0))}%`,
                background: 'linear-gradient(90deg, #E8A55B80, #E8DCC4)',
              }}
            />
          </div>
          <p className="font-inter text-[10px] text-[#CCCCCC] opacity-70 mt-1.5">
            {currentPartTreatment ? 'Не двигайтесь, действие выполняется.' : `Сейчас лечится: ${treatment.partLabel || treatment.partId}`}
          </p>
        </div>
      )}

      <div className="grid gap-2">
        {options.length === 0 && (
          <p className="font-inter text-xs text-[#CCCCCC] opacity-75">
            Для этой зоны сейчас нет нужных медицинских действий.
          </p>
        )}
        {options.map(option => (
          <button
            type="button"
            key={option.id}
            disabled={activeTreatment}
            onClick={() => onTreat(option.id)}
            className="rounded border border-[rgba(232,220,196,0.16)] bg-[rgba(10,10,10,0.30)] p-2 text-left transition-all duration-200 disabled:opacity-45 disabled:cursor-not-allowed hover:border-[rgba(232,220,196,0.35)]"
          >
            <div className="flex items-center justify-between gap-2">
              <span className="font-cinzel text-[12px] text-[#E8DCC4] tracking-wider">{option.label}</span>
              <span className="font-inter text-[10px] text-[#E8A55B]">{option.duration}</span>
            </div>
            <p className="font-inter text-[10px] text-[#CCCCCC] opacity-75 mt-1">{option.description}</p>
            <p className="font-inter text-[9px] text-[#A5C3C4] opacity-80 mt-1">Нужно: {option.item}</p>
          </button>
        ))}
      </div>

      {notice && (
        <p className="font-inter text-[10px] text-[#E8A55B] mt-2">{notice}</p>
      )}
    </div>
  );
}

function treatmentOptions(partKey: Exclude<BodyPart, null>, part: VitalsPart): TreatmentOption[] {
  if (Array.isArray(part.actions)) {
    return part.actions
      .filter(action => action.enabled !== false)
      .map(action => ({
        ...action,
        item: action.item || 'не требуется',
        duration: action.duration || durationFromTicks(action.durationTicks),
        description: action.description || '',
      }));
  }

  const options: TreatmentOption[] = [];
  const bleeding = part.bleeding ?? 0;
  const burn = part.burn ?? 0;
  const pain = part.pain ?? 0;

  if (bleeding > 0.1 || (part.openWound && !part.bandaged)) {
    options.push({
      id: 'bandage',
      label: 'Перевязать',
      item: 'бумага или белая шерсть',
      duration: '4с',
      description: 'Останавливает кровотечение и закрывает открытую рану.',
    });
  }
  if (isLimb(partKey) && bleeding > 6 && !part.tourniquet) {
    options.push({
      id: 'tourniquet',
      label: 'Наложить жгут',
      item: 'нитка или поводок',
      duration: '2.8с',
      description: 'Экстренно останавливает сильное кровотечение конечности, но опасен при долгом ношении.',
    });
  }
  if (part.tourniquet) {
    options.push({
      id: 'release_tourniquet',
      label: 'Снять жгут',
      item: 'не требуется',
      duration: '2.3с',
      description: 'Снимает давление с конечности. Если рана не перевязана, кровь может пойти снова.',
    });
  }
  if ((part.openWound || burn > 0 || part.infection) && !part.woundCleaned) {
    options.push({
      id: 'clean_wound',
      label: 'Обработать рану',
      item: 'мед, зелье или стеклянная бутылка',
      duration: '4.5с',
      description: 'Снижает риск инфекции и немного уменьшает боль.',
    });
  }
  if (burn > 8 && !part.burnTreated) {
    options.push({
      id: 'treat_burn',
      label: 'Охладить ожог',
      item: 'снежок',
      duration: '3.5с',
      description: 'Уменьшает ожог, боль и риск дальнейших осложнений.',
    });
  }
  if (isLimb(partKey) && (part.fracture || (part.condition ?? 100) < 22) && !part.fractureStabilized) {
    options.push({
      id: 'splint',
      label: 'Наложить шину',
      item: 'палка или бамбук',
      duration: '7.5с',
      description: 'Стабилизирует перелом и ослабляет штрафы движения.',
    });
  }
  if (part.embeddedArrow) {
    options.push({
      id: 'extract_arrow',
      label: 'Извлечь стрелу',
      item: 'ножницы',
      duration: '6с',
      description: 'Извлекает стрелу, но усиливает боль и открывает рану.',
    });
  }
  if (pain > 12 && !part.medicated) {
    options.push({
      id: 'painkiller',
      label: 'Обезболить',
      item: 'сахар',
      duration: '3с',
      description: 'Временно приглушает боль, но не лечит саму травму.',
    });
  }
  if (part.infection && !part.antibiotics) {
    options.push({
      id: 'antibiotic',
      label: 'Антибиотик',
      item: 'ферментированный паучий глаз',
      duration: '3.5с',
      description: 'Запускает курс лечения инфекции и ослабляет отравление.',
    });
  }

  return options;
}

function durationFromTicks(ticks?: number) {
  if (!ticks || ticks <= 0) return '';
  const seconds = ticks / 20;
  return `${Number.isInteger(seconds) ? seconds : seconds.toFixed(1)}с`;
}

function isLimb(part: Exclude<BodyPart, null>) {
  return part === 'leftArm' || part === 'rightArm' || part === 'leftLeg' || part === 'rightLeg';
}

function partLabel(part: Exclude<BodyPart, null>) {
  switch (part) {
    case 'head': return 'Голова';
    case 'torso': return 'Торс';
    case 'leftArm': return 'Левая рука';
    case 'rightArm': return 'Правая рука';
    case 'leftLeg': return 'Левая нога';
    case 'rightLeg': return 'Правая нога';
  }
}

function mapBodyParts(parts: VitalsPart[]): BodyPartDetails {
  const byId = new Map(parts.map(part => [part.id, part]));
  return {
    head: normalizePart(byId.get('head'), EMPTY_PARTS.head),
    torso: normalizePart(byId.get('chest'), EMPTY_PARTS.torso),
    leftArm: normalizePart(byId.get('leftArm'), EMPTY_PARTS.leftArm),
    rightArm: normalizePart(byId.get('rightArm'), EMPTY_PARTS.rightArm),
    leftLeg: normalizePart(byId.get('leftLeg'), EMPTY_PARTS.leftLeg),
    rightLeg: normalizePart(byId.get('rightLeg'), EMPTY_PARTS.rightLeg),
  };
}

function normalizePart(part: VitalsPart | undefined, fallback: VitalsPart): VitalsPart {
  return {
    ...fallback,
    ...part,
    condition: clamp(Number(part?.condition ?? fallback.condition ?? 100), 0, 100),
    bleeding: clamp(Number(part?.bleeding ?? fallback.bleeding ?? 0), 0, 100),
    pain: clamp(Number(part?.pain ?? fallback.pain ?? 0), 0, 100),
    burn: clamp(Number(part?.burn ?? fallback.burn ?? 0), 0, 100),
    state: stateForPart({ ...fallback, ...part }),
  };
}

function mapBodyStatuses(parts: BodyPartDetails): BodyStatuses {
  return {
    head: stateForPart(parts.head),
    torso: stateForPart(parts.torso),
    leftArm: stateForPart(parts.leftArm),
    rightArm: stateForPart(parts.rightArm),
    leftLeg: stateForPart(parts.leftLeg),
    rightLeg: stateForPart(parts.rightLeg),
  };
}

function stateForPart(part: VitalsPart): string {
  if (part.infection) return 'Инфекция';
  if (part.fracture) return 'Перелом';
  if ((part.burn ?? 0) > 25) return 'Ожог';
  if ((part.bleeding ?? 0) > 10) return 'Кровотечение';
  if (part.openWound) return 'Ранение';
  const condition = clamp(Number(part.condition ?? 100), 0, 100);
  if (condition < 35) return 'Тяжелая травма';
  if (condition < 65) return 'Травма';
  if (condition < 88) return 'Легкое повреждение';
  return NORMAL;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export default App;
