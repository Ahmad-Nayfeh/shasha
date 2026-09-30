import React from 'react';
import {Scene} from '../lib/timeline';
import {Fallback} from './Fallback';
import {S01Intro} from './S01Intro';
import {S02Why} from './S02Why';
import {S03HomeSearch} from './S03HomeSearch';
import {S04Player} from './S04Player';
import {S05Profiles} from './S05Profiles';
import {S06Time} from './S06Time';
import {S07Lock} from './S07Lock';
import {S08Guard} from './S08Guard';
import {S09Phone} from './S09Phone';
import {S10Stats} from './S10Stats';
import {S11Install} from './S11Install';
import {S12Download} from './S12Download';
import {S13Folder} from './S13Folder';
import {S14DevMode} from './S14DevMode';
import {S15Debugging} from './S15Debugging';
import {S16FindIp} from './S16FindIp';
import {S17RunWindows} from './S17RunWindows';
import {S18RunMac} from './S18RunMac';
import {S19TypeIp} from './S19TypeIp';
import {S20Allow} from './S20Allow';
import {S21Pairing} from './S21Pairing';
import {S22Automatic} from './S22Automatic';
import {S23FirstStart} from './S23FirstStart';
import {S24Phone} from './S24Phone';
import {S14Breaks} from './S14Breaks';
import {S15Disclaimer} from './S15Disclaimer';
import {S16Outro} from './S16Outro';

export type SceneComponent = React.FC<{scene: Scene}>;

export const SCENES: Record<string, SceneComponent> = {
  s01_intro: S01Intro,
  s02_why: S02Why,
  s03_home_search: S03HomeSearch,
  s04_player: S04Player,
  s05_profiles: S05Profiles,
  s06_time: S06Time,
  s07_lock: S07Lock,
  s08_guard: S08Guard,
  s09_phone: S09Phone,
  s10_stats: S10Stats,
  // installation, INSTALL_FLOW v2 (nine steps)
  s11_install: S11Install,
  s12_download: S12Download,
  s13_folder: S13Folder,
  s14_devmode: S14DevMode,
  s15_debugging: S15Debugging,
  s16_find_ip: S16FindIp,
  s17_run_windows: S17RunWindows,
  s18_run_mac: S18RunMac,
  s19_type_ip: S19TypeIp,
  s20_allow: S20Allow,
  s21_pairing: S21Pairing,
  s22_automatic: S22Automatic,
  s23_first_start: S23FirstStart,
  s24_phone: S24Phone,
  // closing (component names kept from v1)
  s25_breaks: S14Breaks,
  s26_disclaimer: S15Disclaimer,
  s27_outro: S16Outro,
};

export const sceneComponent = (id: string): SceneComponent => SCENES[id] ?? Fallback;

// Scenes whose narration is already shown as on-screen kinetic text (captions would duplicate it).
export const NO_CAPTIONS = new Set<string>(['s27_outro']);
