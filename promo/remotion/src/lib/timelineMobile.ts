// The phone video's timeline (promo/timeline-mobile.json, written by tts/scripts/eleven_narration.py): the same
// scene model as the TV video; beatsFor / captionChunks / sceneWords in ./timeline work on both.
// The English version (./lang.ts) reads timeline-mobile-en.json.
import timelineMobileArJson from '../data/timeline-mobile.json';
import timelineMobileEnJson from '../data/timeline-mobile-en.json';
import {isEn} from './lang';
import type {Scene} from './timeline';

type MobileTimeline = {fps: number; width: number; height: number; total_frames: number; scenes: Scene[]};

export const mobileTimeline = (isEn ? timelineMobileEnJson : timelineMobileArJson) as unknown as MobileTimeline;
export const mobileScenes = mobileTimeline.scenes;
export const mobileSceneById = (id: string): Scene => {
  const s = mobileScenes.find((x) => x.id === id);
  if (!s) throw new Error('Unknown phone scene ' + id);
  return s;
};
