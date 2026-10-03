import React from 'react';
import {Scene} from '../lib/timeline';
import {Fallback} from './Fallback';
import {S01Intro} from './S01Intro';
import {S02Why} from './S02Why';
import {S03Home} from './S03Home';
import {S05Profiles} from './S05Profiles';
import {S06Time} from './S06Time';
import {S07Lock} from './S07Lock';
import {S08Guard} from './S08Guard';
import {S08Suggest} from './S08Suggest';
import {S09Channel} from './S09Channel';
import {S10Search} from './S10Search';
import {S09Phone} from './S09Phone';
import {S13Accept} from './S13Accept';
import {S15Updates} from './S15Updates';
import {S16Tvs} from './S16Tvs';
import {S17Stick} from './S17Stick';
import {S11Install} from './S11Install';
import {S12Download} from './S12Download';
import {S14DevMode} from './S14DevMode';
import {S15Debugging} from './S15Debugging';
import {S16FindIp} from './S16FindIp';
import {S17RunWindows} from './S17RunWindows';
import {S18RunMac} from './S18RunMac';
import {S19TypeIp} from './S19TypeIp';
import {S20Allow} from './S20Allow';
import {S22Automatic} from './S22Automatic';
import {S23FirstStart} from './S23FirstStart';
import {S30PhoneApp} from './S30PhoneApp';
import {S16Outro} from './S16Outro';
import {M01Intro} from './M01Intro';
import {M02Download} from './M02Download';
import {M03Allow} from './M03Allow';
import {M05Search} from './M05Search';
import {M06Channel} from './M06Channel';
import {M07Background} from './M07Background';
import {M08Pip} from './M08Pip';
import {M09Speed} from './M09Speed';
import {M10Sponsor} from './M10Sponsor';
import {M11Downloads} from './M11Downloads';
import {M12Subscriptions} from './M12Subscriptions';
import {M13History} from './M13History';
import {M14Update} from './M14Update';

export type SceneComponent = React.FC<{scene: Scene}>;

// Scene ids are the names of the narration's audio files (tv_<id>_NN.mp3): the numbering has gaps where scenes of
// the first draft were cut after the audio was made. s.. = the TV video, m.. = the phone video.
export const SCENES: Record<string, SceneComponent> = {
  // the TV video
  s01_intro: S01Intro,
  s02_why: S02Why,
  s03_home: S03Home,
  s04_profiles: S05Profiles,
  s05_time: S06Time,
  s06_serious: S07Lock,
  s07_guard: S08Guard,
  s08_suggest: S08Suggest,
  s09_channel: S09Channel,
  s10_search: S10Search,
  s12_phone: S09Phone,
  s13_accept: S13Accept,
  s15_updates: S15Updates,
  s16_tvs: S16Tvs,
  s17_stick: S17Stick,
  // installation: eight steps
  s18_need: S11Install,
  s19_download: S12Download,
  s21_devmode: S14DevMode,
  s22_debugging: S15Debugging,
  s23_find_ip: S16FindIp,
  s24_run_windows: S17RunWindows,
  s25_run_mac: S18RunMac,
  s26_type_ip: S19TypeIp,
  s27_allow: S20Allow,
  s28_automatic: S22Automatic,
  s29_first_start: S23FirstStart,
  // closing
  s30_phone_app: S30PhoneApp,
  s31_outro: S16Outro,
  // the phone video
  m01_intro: M01Intro,
  m02_download: M02Download,
  m03_allow: M03Allow,
  m05_search: M05Search,
  m06_channel: M06Channel,
  m07_background: M07Background,
  m08_pip: M08Pip,
  m09_speed: M09Speed,
  m10_sponsor: M10Sponsor,
  m11_downloads: M11Downloads,
  m12_subscriptions: M12Subscriptions,
  m13_history: M13History,
  m14_update: M14Update,
  m15_outro: S16Outro,
};

export const sceneComponent = (id: string): SceneComponent => SCENES[id] ?? Fallback;

// Scenes whose narration is already shown as on-screen kinetic text (captions would duplicate it).
export const NO_CAPTIONS = new Set<string>(['s31_outro', 'm15_outro']);
