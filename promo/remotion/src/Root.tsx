import React from 'react';
import {Composition} from 'remotion';
import {TestArabic} from './TestArabic';
import {Main, mainDuration, MainProps} from './Main';
import {ScreensDebug, screensDebugCount} from './ScreensDebug';
import {Poster} from './Poster';

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="Main"
      component={Main}
      durationInFrames={mainDuration}
      fps={30}
      width={1920}
      height={1080}
      defaultProps={{captions: true, music: true, grain: true} satisfies MainProps}
    />
    <Composition id="ScreensDebug" component={ScreensDebug} durationInFrames={screensDebugCount} fps={30} width={1920} height={1080} />
    <Composition id="Poster" component={Poster} durationInFrames={1} fps={30} width={1920} height={1080} />
    <Composition id="TestArabic" component={TestArabic} durationInFrames={150} fps={30} width={1920} height={1080} />
  </>
);
