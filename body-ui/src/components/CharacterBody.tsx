import { memo, useCallback, useState } from 'react';

export type BodyPart = 'head' | 'torso' | 'leftArm' | 'rightArm' | 'leftLeg' | 'rightLeg' | null;

interface CharacterBodyProps {
  onPartHover: (part: BodyPart) => void;
  onPartClick: (part: BodyPart) => void;
  activePart: BodyPart;
  headStatus: string;
  torsoStatus: string;
  leftArmStatus: string;
  rightArmStatus: string;
  leftLegStatus: string;
  rightLegStatus: string;
}

const PART_LABELS: Record<Exclude<BodyPart, null>, string> = {
  head: 'Голова',
  torso: 'Торс',
  leftArm: 'Левая рука',
  rightArm: 'Правая рука',
  leftLeg: 'Левая нога',
  rightLeg: 'Правая нога',
};

export const CharacterBody: React.FC<CharacterBodyProps> = ({
  onPartHover,
  onPartClick,
  activePart,
  headStatus,
  torsoStatus,
  leftArmStatus,
  rightArmStatus,
  leftLegStatus,
  rightLegStatus,
}) => {
  const [hoveredPart, setHoveredPart] = useState<BodyPart>(null);

  const handleMouseEnter = useCallback((part: BodyPart) => {
    setHoveredPart(part);
    onPartHover(part);
  }, [onPartHover]);

  const handleMouseLeave = useCallback(() => {
    setHoveredPart(null);
    onPartHover(null);
  }, [onPartHover]);

  const handleClick = useCallback((part: BodyPart) => {
    onPartClick(part);
  }, [onPartClick]);

  const isPartActive = (part: BodyPart) => activePart === part || hoveredPart === part;
  const hoveredStatus = hoveredPart ? statusForPart(hoveredPart, {
    headStatus,
    torsoStatus,
    leftArmStatus,
    rightArmStatus,
    leftLegStatus,
    rightLegStatus,
  }) : '';

  return (
    <div className="relative flex items-center justify-center">
      <svg
        viewBox="0 0 200 380"
        className="w-56 h-auto"
        style={{ filter: 'drop-shadow(0 0 20px rgba(0,0,0,0.5))' }}
      >
        <defs>
          <linearGradient id="bodyGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#2D4A3E" stopOpacity="0.62" />
            <stop offset="100%" stopColor="#1A1A1A" stopOpacity="0.84" />
          </linearGradient>
        </defs>

        <BodyShape
          part="head"
          active={isPartActive('head')}
          status={headStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 100, y: 85 }}
          paths={[
            'M70 25 C70 8,85 0,100 0 C115 0,130 8,130 25 L128 55 C128 65,115 72,100 72 C85 72,72 65,72 55 Z',
            'M75 38 L125 38',
          ]}
        />

        <BodyShape
          part="torso"
          active={isPartActive('torso')}
          status={torsoStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 100, y: 178 }}
          paths={[
            'M65 78 L68 140 C68 150,75 158,85 162 L100 168 L115 162 C125 158,132 150,132 140 L135 78 C135 72,125 68,100 68 C75 68,65 72,65 78 Z',
            'M72 95 L128 95 M70 115 L130 115 M70 135 L130 135',
            'M100 78 L100 165',
          ]}
        />

        <BodyShape
          part="leftArm"
          active={isPartActive('leftArm')}
          status={leftArmStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 48, y: 165 }}
          paths={[
            'M65 82 L40 105 L35 140 L42 155 L52 145 L55 115 L65 95 Z',
            'M42 115 L52 118',
          ]}
        />

        <BodyShape
          part="rightArm"
          active={isPartActive('rightArm')}
          status={rightArmStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 152, y: 165 }}
          paths={[
            'M135 82 L160 105 L165 140 L158 155 L148 145 L145 115 L135 95 Z',
            'M148 115 L138 118',
          ]}
        />

        <BodyShape
          part="leftLeg"
          active={isPartActive('leftLeg')}
          status={leftLegStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 74, y: 355 }}
          paths={[
            'M82 165 L75 220 L72 280 L65 340 L78 345 L85 285 L90 225 L95 168 Z',
            'M76 200 L90 202 M74 240 L88 242 M70 300 L82 302',
          ]}
        />

        <BodyShape
          part="rightLeg"
          active={isPartActive('rightLeg')}
          status={rightLegStatus}
          onEnter={handleMouseEnter}
          onLeave={handleMouseLeave}
          onClick={handleClick}
          indicator={{ x: 126, y: 355 }}
          paths={[
            'M118 165 L125 220 L128 280 L135 340 L122 345 L115 285 L110 225 L105 168 Z',
            'M124 200 L110 202 M126 240 L112 242 M130 300 L118 302',
          ]}
        />
      </svg>

      {hoveredPart && (
        <div
          className="absolute pointer-events-none tooltip-glass px-3 py-2 rounded fade-in"
          style={{
            top: hoveredPart === 'head' ? '7%' :
                 hoveredPart === 'torso' ? '35%' :
                 hoveredPart === 'leftArm' || hoveredPart === 'rightArm' ? '34%' : '76%',
            right: hoveredPart === 'rightArm' || hoveredPart === 'rightLeg' ? '8%' : 'auto',
            left: hoveredPart === 'rightArm' || hoveredPart === 'rightLeg' ? 'auto' : '55%',
            transform: 'translateY(-50%)',
            zIndex: 50,
          }}
        >
          <p className="font-cinzel text-xs text-[#E8DCC4] tracking-wider">
            {PART_LABELS[hoveredPart]}
          </p>
          <p className="font-inter text-[10px] text-[#CCCCCC] mt-0.5">{hoveredStatus}</p>
        </div>
      )}
    </div>
  );
};

interface BodyShapeProps {
  part: Exclude<BodyPart, null>;
  active: boolean;
  status: string;
  indicator: { x: number; y: number };
  paths: string[];
  onEnter: (part: BodyPart) => void;
  onLeave: () => void;
  onClick: (part: BodyPart) => void;
}

function BodyShape({ part, active, status, indicator, paths, onEnter, onLeave, onClick }: BodyShapeProps) {
  return (
    <g
      className={`body-part ${active ? 'active' : ''}`}
      onMouseEnter={() => onEnter(part)}
      onMouseLeave={onLeave}
      onClick={() => onClick(part)}
    >
      {paths.map((path, index) => (
        <path
          key={path}
          d={path}
          fill={index === 0 ? 'url(#bodyGrad)' : 'none'}
          stroke={active ? '#FFFFFF' : index === 0 ? '#E8DCC4' : 'rgba(232, 220, 196, 0.28)'}
          strokeWidth={active ? 2.5 : index === 0 ? 1.5 : 1}
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{ transition: 'all 0.28s cubic-bezier(0.2, 1, 0.3, 1)' }}
        />
      ))}
      <circle
        cx={indicator.x}
        cy={indicator.y}
        r="4"
        fill={getStatusColor(status)}
        opacity={active ? 1 : 0.65}
        style={{ transition: 'all 0.22s ease' }}
      />
    </g>
  );
}

function statusForPart(part: Exclude<BodyPart, null>, statuses: Record<string, string>): string {
  switch (part) {
    case 'head': return statuses.headStatus;
    case 'torso': return statuses.torsoStatus;
    case 'leftArm': return statuses.leftArmStatus;
    case 'rightArm': return statuses.rightArmStatus;
    case 'leftLeg': return statuses.leftLegStatus;
    case 'rightLeg': return statuses.rightLegStatus;
  }
}

function getStatusColor(status: string) {
  switch (status.toLowerCase()) {
    case 'нормально':
    case 'відпочиває':
      return '#7BC67E';
    case 'легке пошкодження':
    case 'легке напруження':
    case 'втома':
    case 'перевтома':
    case 'травма':
    case 'кровотеча':
    case 'опік':
      return '#E8A55B';
    case 'поранення':
    case 'перелом':
    case 'інфекція':
    case 'важка травма':
    case 'критично':
      return '#E85D5D';
    default:
      return '#7BC67E';
  }
}

export default memo(CharacterBody);
