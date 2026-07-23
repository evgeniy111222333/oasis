import { memo, useMemo, useState, useCallback } from 'react';
import type { ReactNode } from 'react';

export interface StatusMetrics {
  stamina: number;
  breathDebt: number;
  tension: number;
  blood: number;
  pain: number;
  bleeding: number;
  unconscious: boolean;
}

interface StaminaPanelProps {
  metrics: StatusMetrics;
  onMetricHover?: (metric: string | null) => void;
}

export const StaminaPanel: React.FC<StaminaPanelProps> = ({ metrics, onMetricHover }) => {
  const [hoveredMetric, setHoveredMetric] = useState<string | null>(null);

  const handleMouseEnter = useCallback((metric: string) => {
    setHoveredMetric(metric);
    onMetricHover?.(metric);
  }, [onMetricHover]);

  const handleMouseLeave = useCallback(() => {
    setHoveredMetric(null);
    onMetricHover?.(null);
  }, [onMetricHover]);

  const metricsData = useMemo(() => [
    metric('stamina', 'Стамина', 'Запас сил', metrics.stamina, '#5B8DEF',
      metrics.stamina > 70 ? 'Полная энергия' : metrics.stamina > 40 ? 'Усталость' : metrics.stamina > 20 ? 'Тяжело' : 'Истощение',
      <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z" />),
    metric('breathDebt', 'Дыхание', 'Долг', metrics.breathDebt, '#8A5BE8',
      metrics.breathDebt > 70 ? 'Задыхается' : metrics.breathDebt > 40 ? 'Одышка' : metrics.breathDebt > 20 ? 'Сбито' : 'Ровно',
      <><path d="M12 2C8 2 5 5 5 9c0 3 2 5 2 8h10c0-3 2-5 2-8 0-4-3-7-7-7z" /><path d="M9 22h6" /><path d="M10 19h4" /></>),
    metric('tension', 'Усталость', 'Напряж.', metrics.tension, '#E8A55B',
      metrics.tension > 70 ? 'Критично' : metrics.tension > 40 ? 'Заметно' : metrics.tension > 20 ? 'Легко' : 'Отдыхает',
      <><path d="M6.5 21H3V8l4-4 4 4v5" /><path d="M10 9h4v12h-4z" /><path d="M17.5 21H14V11l3-3 3 3v4" /><path d="M2 21h20" /></>),
    metric('blood', 'Кровь', 'Запас', metrics.blood, '#C84B4B',
      metrics.blood > 80 ? 'Норма' : metrics.blood > 55 ? 'Потеря' : metrics.blood > 30 ? 'Опасно' : 'Критично',
      <path d="M12 2C8 7 5 11 5 15a7 7 0 0 0 14 0c0-4-3-8-7-13z" />),
    metric('pain', 'Боль', 'Симптом', metrics.pain, '#D68A5A',
      metrics.pain > 75 ? 'Сильная' : metrics.pain > 40 ? 'Мешает' : metrics.pain > 10 ? 'Есть' : 'Нет',
      <><path d="M12 3v6" /><path d="M12 15v6" /><path d="M3 12h6" /><path d="M15 12h6" /><path d="M5.6 5.6l4.2 4.2" /><path d="M14.2 14.2l4.2 4.2" /></>),
    metric('bleeding', 'Потеря', 'Кровь', metrics.bleeding, '#B83C3C',
      metrics.bleeding > 20 ? 'Сильная' : metrics.bleeding > 5 ? 'Идет' : metrics.bleeding > 0 ? 'Слабая' : 'Нет',
      <><path d="M12 2v20" /><path d="M7 7h10" /><path d="M8 14h8" /></>),
  ], [metrics]);

  return (
    <div className="grid grid-cols-2 gap-2">
      {metricsData.map((item, index) => (
        <button
          type="button"
          key={item.key}
          className={`glass-panel rounded-lg p-2.5 text-left cursor-pointer transition-all duration-200 ${
            hoveredMetric === item.key ? 'border-[rgba(232,220,196,0.28)]' : ''
          } ${metrics.unconscious && item.key === 'pain' ? 'pulse-glow' : ''}`}
          style={{ animationDelay: `${index * 35}ms` }}
          onMouseEnter={() => handleMouseEnter(item.key)}
          onMouseLeave={handleMouseLeave}
        >
          <div className="flex items-center justify-between gap-2 mb-1.5">
            <div className="flex items-center gap-2 min-w-0">
              <span style={{ color: hoveredMetric === item.key ? '#FFFFFF' : '#E8DCC4' }}>
                <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
                  {item.icon}
                </svg>
              </span>
              <div className="min-w-0">
                <h3 className="font-cinzel text-[12px] tracking-wider text-[#E8DCC4] truncate">{item.label}</h3>
                <p className="font-inter text-[9px] text-[#CCCCCC] opacity-60 truncate">{item.labelShort}</p>
              </div>
            </div>
            <span className="font-cinzel text-sm shrink-0" style={{ color: item.color }}>
              {Math.round(item.value)}%
            </span>
          </div>

          <div className="w-full h-1.5 rounded-full bg-[rgba(204,204,204,0.08)] overflow-hidden">
            <div
              className="h-full rounded-full transition-all duration-500 ease-out"
              style={{
                width: `${item.value}%`,
                background: `linear-gradient(90deg, ${item.color}80, ${item.color})`,
                boxShadow: `0 0 10px ${item.color}40`,
              }}
            />
          </div>
          <div className="flex justify-between items-center mt-1.5">
            <div className="flex gap-1">{renderDots(item.value, item.color)}</div>
            <span className="font-inter text-[9px] truncate ml-2" style={{ color: item.color, opacity: 0.85 }}>
              {item.description}
            </span>
          </div>
        </button>
      ))}
    </div>
  );
};

function metric(key: string, label: string, labelShort: string, value: number, color: string, description: string, icon: ReactNode) {
  return { key, label, labelShort, value, color, description, icon };
}

function renderDots(value: number, color: string) {
  const activeDots = Math.ceil(value / 20);
  return Array.from({ length: 5 }, (_, i) => (
    <span
      key={i}
      className={value <= 30 && i < activeDots && value > 0 ? 'pulse-glow' : ''}
      style={{
        width: '6px',
        height: '6px',
        borderRadius: '50%',
        backgroundColor: i < activeDots ? color : 'rgba(204, 204, 204, 0.12)',
        boxShadow: i < activeDots ? `0 0 6px ${color}40` : 'none',
      }}
    />
  ));
}

export default memo(StaminaPanel);
