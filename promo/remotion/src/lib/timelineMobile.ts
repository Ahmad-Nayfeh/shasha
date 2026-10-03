// The phone video's timeline (promo/timeline-mobile.json, written by tts/scripts/eleven_narration.py): the same
// scene model as the TV video; beatsFor / captionChunks / sceneWords in ./timeline work on both.
import timelineMobileJson from '../data/timeline-mobile.json';
import type {Scene} from './timeline';

type MobileTimeline = {fps: number; width: number; height: number; total_frames: number; scenes: Scene[]};

export const mobileTimeline = timelineMobileJson as unknown as MobileTimeline;
export const mobileScenes = mobileTimeline.scenes;
export const mobileSceneById = (id: string): Scene => {
  const s = mobileScenes.find((x) => x.id === id);
  if (!s) throw new Error('Unknown phone scene ' + id);
  return s;
};
