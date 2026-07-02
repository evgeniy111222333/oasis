import { memo, useMemo } from 'react';
import type { BodyPart } from './CharacterBody';
import type { BodyPartDetails } from '../App';
import type { StatusMetrics } from './StaminaPanel';

interface StatusDescriptionProps {
  activePart: BodyPart;
  parts: BodyPartDetails;
  hoveredMetric: string | null;
  metrics: StatusMetrics;
}

interface InfoData {
  title: string;
  badge: string;
  badgeType: 'status' | 'value' | 'none';
  badgeColor: string;
  details: string;
  penalty: string;
  danger: boolean;
}

const NORMAL = 'Нормально';

const StatusDescriptionComponent: React.FC<StatusDescriptionProps> = ({
  activePart,
  parts,
  hoveredMetric,
  metrics,
}) => {
  const info = useMemo(() => {
    if (hoveredMetric) {
      return getMetricInfo(hoveredMetric, metrics);
    }
    if (activePart) {
      return getPartInfo(activePart, parts[activePart]);
    }
    return getDefaultInfo(parts, metrics);
  }, [activePart, hoveredMetric, metrics, parts]);

  return (
    <div className="glass-panel rounded-lg p-3 fade-in">
      <div className="flex items-center gap-3 mb-2">
        <div
          className="w-2 h-2 rounded-full"
          style={{
            backgroundColor: info.danger ? '#E8A55B' : '#7BC67E',
            boxShadow: info.danger ? '0 0 8px rgba(232, 165, 91, 0.5)' : '0 0 8px rgba(123, 198, 126, 0.5)',
          }}
        />
        <h3 className="font-cinzel text-sm tracking-wider text-[#E8DCC4]">{info.title}</h3>
        {info.badgeType === 'status' && (
          <span
            className="font-inter text-[11px] px-2 py-0.5 rounded-full ml-auto"
            style={{
              backgroundColor: info.danger ? 'rgba(232, 165, 91, 0.15)' : 'rgba(123, 198, 126, 0.15)',
              color: info.danger ? '#E8A55B' : '#7BC67E',
            }}
          >
            {info.badge}
          </span>
        )}
        {info.badgeType === 'value' && (
          <span className="font-cinzel text-sm ml-auto" style={{ color: info.badgeColor }}>{info.badge}</span>
        )}
      </div>

      <div className="ornament-line mb-2" />

      <p className="font-inter text-xs text-[#CCCCCC] leading-relaxed mb-2">{info.details}</p>

      <div
        className="font-inter text-[11px] px-3 py-2 rounded"
        style={{
          backgroundColor: info.danger ? 'rgba(232, 165, 91, 0.08)' : 'rgba(123, 198, 126, 0.08)',
          color: info.danger ? '#E8A55B' : '#7BC67E',
          borderLeft: `2px solid ${info.danger ? '#E8A55B' : '#7BC67E'}`,
        }}
      >
        {info.penalty}
      </div>
    </div>
  );
};

function getPartInfo(part: Exclude<BodyPart, null>, data: BodyPartDetails[Exclude<BodyPart, null>]): InfoData {
  const state = localizedPartState(data);
  const condition = Math.round(data.condition ?? 100);
  const symptoms = partSymptoms(data);
  const danger = state !== NORMAL || symptoms.length > 0 || condition < 88;
  const profile = bodyPartProfile(part);

  return {
    title: profile.title,
    badge: state,
    badgeType: 'status',
    badgeColor: danger ? '#E8A55B' : '#7BC67E',
    details: `${profile.details} Состояние тканей: ${condition}%. ${symptoms.length ? `Признаки: ${symptoms.join(', ')}.` : 'Признаков травмы нет.'}`,
    penalty: danger ? profile.penalty : 'Штрафов нет',
    danger,
  };
}

function getMetricInfo(metric: string, metrics: StatusMetrics): InfoData {
  switch (metric) {
    case 'stamina':
      return {
        title: 'Стамина',
        badge: `${Math.round(metrics.stamina)}%`,
        badgeType: 'value',
        badgeColor: '#5B8DEF',
        details: 'Главный ресурс движения: спринт, прыжки, рывки и активные действия. Травмы торса, боль и кровопотеря замедляют восстановление.',
        penalty: metrics.stamina < 30 ? 'Спринт нестабилен, дыхание тяжелое' : 'Работает нормально',
        danger: metrics.stamina < 50,
      };
    case 'breathDebt':
      return {
        title: 'Дыхание',
        badge: `${Math.round(metrics.breathDebt)}%`,
        badgeType: 'value',
        badgeColor: '#8A5BE8',
        details: 'Показывает, насколько персонаж сбил дыхание из-за нагрузки, боли или травмы торса.',
        penalty: metrics.breathDebt > 60 ? 'Спринт чаще блокируется' : 'Дыхание стабильное',
        danger: metrics.breathDebt > 40,
      };
    case 'tension':
      return {
        title: 'Усталость',
        badge: `${Math.round(metrics.tension)}%`,
        badgeType: 'value',
        badgeColor: '#E8A55B',
        details: 'Накопленная усталость от действий и повреждений. Спадает постепенно, без резкого сброса.',
        penalty: metrics.tension > 60 ? 'Действия ощущаются тяжелее' : 'Штрафов нет',
        danger: metrics.tension > 40,
      };
    case 'blood':
      return {
        title: 'Кровь',
        badge: `${Math.round(metrics.blood)}%`,
        badgeType: 'value',
        badgeColor: '#C84B4B',
        details: 'Общий запас крови. Кровотечение постепенно снижает его и усиливает потемнение в глазах.',
        penalty: metrics.blood < 45 ? 'Высокий риск потери сознания' : 'Критической кровопотери нет',
        danger: metrics.blood < 70,
      };
    case 'pain':
      return {
        title: 'Боль',
        badge: `${Math.round(metrics.pain)}%`,
        badgeType: 'value',
        badgeColor: '#D68A5A',
        details: 'Суммарная боль от ран, переломов, ожогов и контузии. Влияет на зрение, движение и риск упасть.',
        penalty: metrics.pain > 65 ? 'Экран темнеет, есть риск нокдауна' : 'Боль под контролем',
        danger: metrics.pain > 35,
      };
    case 'bleeding':
      return {
        title: 'Кровотечение',
        badge: `${Math.round(metrics.bleeding)}%`,
        badgeType: 'value',
        badgeColor: '#B83C3C',
        details: 'Открытые раны оставляют следы крови и тянут запас крови вниз, пока рану не обработать.',
        penalty: metrics.bleeding > 5 ? 'Нужно остановить кровь' : 'Активной кровопотери нет',
        danger: metrics.bleeding > 0,
      };
    default:
      return emptyInfo();
  }
}

function getDefaultInfo(parts: BodyPartDetails, metrics: StatusMetrics): InfoData {
  const issues: string[] = [];
  Object.entries(parts).forEach(([key, part]) => {
    const state = localizedPartState(part);
    if (state !== NORMAL || (part.condition ?? 100) < 88) {
      issues.push(`${bodyPartProfile(key as Exclude<BodyPart, null>).title}: ${state}`);
    }
  });
  if (metrics.blood < 70) issues.push(`кровь: ${Math.round(metrics.blood)}%`);
  if (metrics.pain > 30) issues.push(`боль: ${Math.round(metrics.pain)}%`);
  if (metrics.bleeding > 0) issues.push(`кровотечение: ${Math.round(metrics.bleeding)}%`);
  if (metrics.unconscious) issues.push('потеря сознания');

  if (issues.length > 0) {
    return {
      title: 'Текущее состояние',
      badge: 'Есть проблемы',
      badgeType: 'status',
      badgeColor: '#E8A55B',
      details: issues.join(' | '),
      penalty: 'Рекомендуется остановить кровь, снизить боль и восстановить дыхание перед нагрузкой',
      danger: true,
    };
  }

  return {
    title: 'Общее состояние',
    badge: 'Хорошо',
    badgeType: 'status',
    badgeColor: '#7BC67E',
    details: 'Системы работают нормально. Повреждений тела нет, дыхание и стамина в пределах нормы.',
    penalty: 'Штрафов нет',
    danger: false,
  };
}

function bodyPartProfile(part: Exclude<BodyPart, null>) {
  switch (part) {
    case 'head':
      return {
        title: 'Голова',
        details: 'Отвечает за обзор, устойчивость, концентрацию и риск контузии.',
        penalty: 'Возможны тошнота, потемнение, звон и нокдаун',
      };
    case 'torso':
      return {
        title: 'Торс',
        details: 'Центр дыхания, крови и общего ресурса выносливости.',
        penalty: 'Стамина восстанавливается медленнее, одышка сильнее',
      };
    case 'leftArm':
      return {
        title: 'Левая рука',
        details: 'Влияет на удержание предметов, блокирование и работу второй руки.',
        penalty: 'Слабость, усталость при добыче, риск выронить предмет',
      };
    case 'rightArm':
      return {
        title: 'Правая рука',
        details: 'Влияет на удары, инструменты и удержание основного предмета.',
        penalty: 'Слабость, усталость при добыче, риск выронить предмет',
      };
    case 'leftLeg':
      return {
        title: 'Левая нога',
        details: 'Влияет на ходьбу, спринт, прыжок и устойчивость.',
        penalty: 'Замедление, блок спринта, боль при прыжке',
      };
    case 'rightLeg':
      return {
        title: 'Правая нога',
        details: 'Влияет на ходьбу, спринт, прыжок и устойчивость.',
        penalty: 'Замедление, блок спринта, боль при прыжке',
      };
  }
}

function localizedPartState(part: BodyPartDetails[Exclude<BodyPart, null>]) {
  if (part.infection) return 'Инфекция';
  if (part.fracture) return 'Перелом';
  if ((part.burn ?? 0) > 25) return 'Ожог';
  if ((part.bleeding ?? 0) > 10) return 'Кровотечение';
  const condition = part.condition ?? 100;
  if (condition < 35) return 'Тяжелая травма';
  if (condition < 65) return 'Травма';
  if (condition < 88) return 'Легкое повреждение';
  return NORMAL;
}

function partSymptoms(part: BodyPartDetails[Exclude<BodyPart, null>]) {
  const symptoms: string[] = [];
  if ((part.bleeding ?? 0) > 0) symptoms.push(`кровотечение ${Math.round(part.bleeding ?? 0)}%`);
  if ((part.pain ?? 0) > 0) symptoms.push(`боль ${Math.round(part.pain ?? 0)}%`);
  if ((part.burn ?? 0) > 0) symptoms.push(`ожог ${Math.round(part.burn ?? 0)}%`);
  if (part.fracture) symptoms.push('перелом');
  if (part.infection) symptoms.push('инфекция');
  if (part.embeddedArrow) symptoms.push('стрела в теле');
  if (part.openWound) symptoms.push('открытая рана');
  if (part.bandaged) symptoms.push('перевязано');
  if (part.woundCleaned) symptoms.push('обработано');
  if (part.fractureStabilized) symptoms.push('шина');
  if (part.tourniquet) symptoms.push('жгут');
  if (part.medicated) symptoms.push('обезболено');
  if (part.antibiotics) symptoms.push('антибиотик');
  if (part.burnTreated) symptoms.push('ожог обработан');
  return symptoms;
}

function emptyInfo(): InfoData {
  return {
    title: '',
    badge: '',
    badgeType: 'none',
    badgeColor: '#7BC67E',
    details: '',
    penalty: '',
    danger: false,
  };
}

export default memo(StatusDescriptionComponent);
