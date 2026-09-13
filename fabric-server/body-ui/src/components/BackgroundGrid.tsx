import { memo } from 'react';

interface BackgroundGridProps {
  activeTiles?: number[];
}

export const BackgroundGrid: React.FC<BackgroundGridProps> = () => <div className="bg-grid" />;

export default memo(BackgroundGrid);
