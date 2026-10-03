import React from 'react';
import {AbsoluteFill, Audio, getStaticFiles, Sequence, staticFile} from 'remotion';
import {brand} from './brand';
import {Background, Grain} from './components/Background';
import {Captions} from './components/Captions';
import {loadFonts} from './fonts';
import {isEn} from './lib/lang';
import {mobileScenes, mobileTimeline} from './lib/timelineMobile';
import {SceneWrap, XFADE} from './Main';
import {NO_CAPTIONS, sceneComponent} from './scenes';

loadFonts();

// The phone video: the same stage language as the TV video, with the phone in the middle. Scenes that all show
// the phone dissolve into each other without sliding (the phone stays where it is); the end card slides in.
// The English version (lib/lang.ts) is the same video mirrored: the scenes, the stage and the phone's screens read
// left to right, with the English narration, captions and music (public/audio-mobile-en).

export type MobileMainProps = {captions?: boolean; music?: boolean; grain?: boolean};

const hasFile = (name: string) => {
  try {
    return getStaticFiles().some((f) => f.name.replace(/^\/+/, '') === name);
  } catch {
    return false;
  }
};

const withPhone = (id: string) => id.startsWith('m') && id !== 'm15_outro';

/** Narration and music folder of the phone video: public/audio-mobile, or public/audio-mobile-en in English. */
const AUDIO = isEn ? 'audio-mobile-en' : 'audio-mobile';

export const MobileMain: React.FC<MobileMainProps> = ({captions = true, music = true, grain = true}) => {
  const withMusic = music && hasFile(`${AUDIO}/music_bed.wav`);
  return (
    <AbsoluteFill style={{backgroundColor: brand.bg}}>
      <Background />
      {mobileScenes.map((s, i) => {
        const C = sceneComponent(s.id);
        const last = i === mobileScenes.length - 1;
        const slideIn = i === 0 || !(withPhone(mobileScenes[i - 1].id) && withPhone(s.id));
        const slideOut = last || !(withPhone(s.id) && withPhone(mobileScenes[i + 1].id));
        return (
          <Sequence key={s.id} from={s.start_frame} durationInFrames={s.duration_frames + (last ? 0 : XFADE)} name={s.id}>
            <SceneWrap scene={s} first={i === 0} last={last} slideIn={slideIn} slideOut={slideOut}>
              <C scene={s} />
            </SceneWrap>
          </Sequence>
        );
      })}
      {captions
        ? mobileScenes
            .filter((s) => !NO_CAPTIONS.has(s.id))
            .map((s) => (
              <Sequence key={'cc' + s.id} from={s.start_frame} durationInFrames={s.duration_frames} name={'captions ' + s.id}>
                <Captions scene={s} />
              </Sequence>
            ))
        : null}
      {grain ? <Grain /> : null}
      <Audio src={staticFile(`${AUDIO}/narration_full.wav`)} />
      {withMusic ? <Audio src={staticFile(`${AUDIO}/music_bed.wav`)} /> : null}
    </AbsoluteFill>
  );
};

export const mobileDuration = mobileTimeline.total_frames;
