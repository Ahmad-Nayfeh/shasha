import React from 'react';
import {AbsoluteFill, Audio, getStaticFiles, Sequence, staticFile, useCurrentFrame} from 'remotion';
import {brand} from './brand';
import {Background, Grain} from './components/Background';
import {Captions} from './components/Captions';
import {loadFonts} from './fonts';
import {clamp, easeInOut, tween} from './lib/anim';
import {IpNote} from './components/Install';
import {IP_NOTE_LAND} from './scenes/S16FindIp';
import {beatsFor, Scene, sceneById, scenes, timeline} from './lib/timeline';
import {NO_CAPTIONS, sceneComponent} from './scenes';

loadFonts();

export type MainProps = {
  /** burned-in Arabic captions (default on) */
  captions?: boolean;
  /** soft synthesized music bed under the voice, if public/audio/music_bed.wav exists (default on) */
  music?: boolean;
  /** film grain overlay (default on) */
  grain?: boolean;
};

export const XFADE = 10; // frames of cross-fade between scenes (+12 px slide, RTL: in from the left)

// Scene pairs that share the same TV framing: pure cross-fade (no slide) so the TV stays put and only
// its content dissolves.
const CONTINUOUS = new Set([
  's03_home_search>s04_player',
  's04_player>s05_profiles',
  's05_profiles>s06_time',
  's06_time>s07_lock',
  's07_lock>s08_guard',
  's08_guard>s09_phone',
  's09_phone>s10_stats',
  // installation: the same laptop / the same TV from one step to the next
  's12_download>s13_folder',
  's14_devmode>s15_debugging',
  's15_debugging>s16_find_ip',
  's17_run_windows>s18_run_mac',
  's18_run_mac>s19_type_ip',
  's20_allow>s21_pairing',
  's23_first_start>s24_phone',
]);

const SceneWrap: React.FC<{scene: Scene; first: boolean; last: boolean; slideIn: boolean; slideOut: boolean; children: React.ReactNode}> = ({
  scene,
  first,
  last,
  slideIn,
  slideOut,
  children,
}) => {
  const f = useCurrentFrame();
  const fin = first ? 1 : tween(f, 0, XFADE, easeInOut);
  const fout = last ? 0 : tween(f, scene.duration_frames, XFADE, easeInOut);
  return (
    <AbsoluteFill
      style={{
        opacity: fin * (1 - fout),
        transform: `translateX(${(slideIn ? (1 - fin) * -12 : 0) + (slideOut ? fout * 12 : 0)}px)`,
        direction: 'rtl',
        fontFamily: brand.font,
        color: brand.text,
      }}
    >
      {children}
    </AbsoluteFill>
  );
};

const hasFile = (name: string) => {
  try {
    return getStaticFiles().some((f) => f.name.replace(/^\/+/, '') === name);
  } catch {
    return false;
  }
};

export const Main: React.FC<MainProps> = ({captions = true, music = true, grain = true}) => {
  const withMusic = music && hasFile('audio/music_bed.wav');
  return (
    <AbsoluteFill style={{backgroundColor: brand.bg}}>
      <Background />
      {scenes.map((s, i) => {
        const C = sceneComponent(s.id);
        const last = i === scenes.length - 1;
        const slideIn = i === 0 || !CONTINUOUS.has(`${scenes[i - 1].id}>${s.id}`);
        const slideOut = last || !CONTINUOUS.has(`${s.id}>${scenes[i + 1].id}`);
        return (
          <Sequence key={s.id} from={s.start_frame} durationInFrames={s.duration_frames + (last ? 0 : XFADE)} name={s.id}>
            <SceneWrap scene={s} first={i === 0} last={last} slideIn={slideIn} slideOut={slideOut}>
              <C scene={s} />
            </SceneWrap>
          </Sequence>
        );
      })}
      <PersistentIpNote />
      {captions
        ? scenes
            .filter((s) => !NO_CAPTIONS.has(s.id))
            .map((s) => (
              <Sequence key={'cc' + s.id} from={s.start_frame} durationInFrames={s.duration_frames} name={'captions ' + s.id}>
                <Captions scene={s} />
              </Sequence>
            ))
        : null}
      {grain ? <Grain /> : null}
      <Audio src={staticFile('audio/narration_full.wav')} />
      {withMusic ? <Audio src={staticFile('audio/music_bed.wav')} /> : null}
    </AbsoluteFill>
  );
};

// The TV's IP, noted in step 4 (s16), stays pinned in the top-left corner through step 5 (s17-s19: one element,
// so it does not flicker in the cross-fades) and glows while it is typed into the installer.
const PersistentIpNote: React.FC = () => {
  const s16 = sceneById('s16_find_ip');
  const s19 = sceneById('s19_type_ip');
  const land = s16.start_frame + beatsFor(s16).at('أرقاما') + IP_NOTE_LAND;
  const end = s19.start_frame + s19.duration_frames + XFADE;
  const typeAt = s19.start_frame + beatsFor(s19).at('اكتب العنوان') + 6 - land;
  return (
    <Sequence from={land} durationInFrames={end - land} name="ip note">
      <IpNoteLayer typeAt={typeAt} total={end - land} />
    </Sequence>
  );
};

const IpNoteLayer: React.FC<{typeAt: number; total: number}> = ({typeAt, total}) => {
  const f = useCurrentFrame();
  const glow = Math.sin(clamp((f - typeAt) / 26) * Math.PI);
  const out = tween(f, total - XFADE, XFADE, easeInOut);
  return (
    <AbsoluteFill style={{opacity: 1 - out}}>
      <IpNote glow={glow} />
    </AbsoluteFill>
  );
};

export const mainDuration = timeline.total_frames;
