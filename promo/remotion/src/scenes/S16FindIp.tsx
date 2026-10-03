import React from 'react';
import {useCurrentFrame} from 'remotion';
import {brand, rgba} from '../brand';
import {Icon} from '../components/Icons';
import {IP_NOTE_HOME, IpNote, mirrorX, NoteChip, StepHeader} from '../components/Install';
import {Press} from '../components/Remote';
import {Crumbs, K, RowHalo, sx, sy, TV, TvStepStage} from '../components/TvStep';
import {clamp, easeInOut, keys, mix, sp, tween} from '../lib/anim';
import {TV_IP} from '../lib/demo';
import {dir, flowSign, isEn, t} from '../lib/lang';
import {beatsFor, Scene} from '../lib/timeline';
import {NETWORK_ROW, P_NETWORK, P_SETTINGS, P_WIFI, rowRect, SETTINGS_ROW, WIFI_ROW} from '../lib/tvPanels';

// Step 4 (TV): Settings → Network & Internet, then two cases.
//  • Wi-Fi: your network → «IP address» (shown on the TV);
//  • cable: the address is the small text right under «Connected» in the «Ethernet» section of the same
//    Network & Internet page, with no «IP address» label (Android TV 12) — shown as a mini panel beside the TV.
// The address then detaches into a note that stays pinned top-left through step 5 (Main.tsx draws that copy).
export const IP_NOTE_LAND = 32; // frames after «أرقاما» when the note has landed (Main.tsx takes over)

/** The «Ethernet» part of Network & Internet, redrawn small beside the TV (the wired case). */
const EthernetCard: React.FC<{p: number; ring: number}> = ({p, ring}) => {
  const tv = "'IBM Plex Sans Arabic', sans-serif";
  const row = (label: string, sub: string) => (
    <div style={{padding: '0 18px', marginTop: 14}}>
      <div style={{fontSize: 27, color: '#E8EAED'}}>{label}</div>
      <div style={{fontSize: 20, color: '#9AA0A6'}}>{sub}</div>
    </div>
  );
  const under = t('العنوان تحت {label} مباشرة').split('{label}');
  return (
    <div
      style={{
        position: 'absolute',
        left: mirrorX(1352, 500),
        top: 250,
        width: 500,
        opacity: clamp(p * 1.4),
        transform: `translateX(${flowSign * (1 - clamp(p)) * 50}px)`,
        fontFamily: brand.font,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 14, direction: dir, marginBottom: 16}}>
        <div style={{width: 54, height: 54, borderRadius: 99, display: 'grid', placeItems: 'center', background: rgba(brand.primary, 0.16), border: `1.5px solid ${rgba(brand.primary, 0.5)}`}}>
          <Icon name="plug" size={30} color={brand.primary} stroke={2.2} />
        </div>
        <div style={{display: 'flex', flexDirection: 'column', lineHeight: 1.2}}>
          <span style={{fontSize: 32, fontWeight: 700, color: brand.text}}>{t('موصولة بسلك؟')}</span>
          <span style={{fontSize: 21, fontWeight: 500, color: brand.muted, direction: 'ltr', textAlign: isEn ? 'left' : 'right'}}>Ethernet</span>
        </div>
      </div>
      <div
        style={{
          borderRadius: 26,
          background: '#202124',
          border: '1.5px solid rgba(234,240,246,0.12)',
          boxShadow: '0 22px 50px rgba(0,0,0,0.5)',
          padding: '26px 22px 26px',
          direction: 'ltr',
          fontFamily: tv,
        }}
      >
        <div style={{fontSize: 30, color: '#E8EAED', padding: '0 18px'}}>Network &amp; Internet</div>
        <div style={{fontSize: 21, color: '#9AA0A6', padding: '0 18px', marginTop: 22, letterSpacing: 0.4}}>Ethernet</div>
        <div style={{marginTop: 8, padding: '12px 18px 14px', borderRadius: 16, background: '#E8EAED', boxShadow: '0 8px 20px rgba(0,0,0,0.35)'}}>
          <div style={{fontSize: 28, fontWeight: 500, color: '#202124'}}>Connected</div>
          <span
            style={{
              display: 'inline-block',
              marginTop: 4,
              padding: '2px 10px',
              marginLeft: -10,
              borderRadius: 10,
              fontSize: 25,
              color: '#3C4043',
              boxShadow: `0 0 0 ${mix(0, 5, ring)}px ${brand.amber}, 0 0 ${24 * ring}px ${rgba(brand.amber, 0.6 * ring)}`,
            }}
          >
            {TV_IP}
          </span>
        </div>
        {row('Proxy settings', 'None')}
        {row('IP settings', 'DHCP')}
      </div>
      <div style={{direction: dir, marginTop: 16, fontSize: 25, fontWeight: 600, color: brand.text, opacity: clamp(ring * 1.5), display: 'flex', alignItems: 'center', gap: 10}}>
        <span style={{color: brand.amber}}>↑</span>
        <span>
          {under[0]}
          <bdi>{isEn ? '“Connected”' : '«Connected»'}</bdi>
          {under[1]}
        </span>
      </div>
    </div>
  );
};

export const S16FindIp: React.FC<{scene: Scene}> = ({scene}) => {
  const f = useCurrentFrame();
  const b = beatsFor(scene);
  const tSettings = b.at('من الإعدادات');
  const tNet = b.at('افتح الشبكة');
  const tWifi = b.at('إن كانت الشاشة');
  const tPick = b.at('فاختر شبكتك');
  const tIp = b.at('ثم آي بي');
  const tWire = b.at('وإن كانت موصولة');
  const tEth = b.at('تحت إيثرنت');
  const tRec = b.at('سجل العنوان');
  const tDigits = b.at('أرقاما');

  const open = tween(f, tSettings - 6, 16, easeInOut);
  const downs0 = [tSettings + 10, tSettings + 16];
  const okNet = Math.max(tNet + 12, downs0[1] + 8);
  const downs1 = [Math.max(tPick + 2, okNet + 16)];
  const okHome = downs1[0] + 10;
  const downIp = Math.max(tIp + 2, okHome + 16);
  const panel = tween(f, okNet + 2, 14, easeInOut) + tween(f, okHome + 2, 14, easeInOut);
  const focus = [
    keys(f, [[downs0[0], 0], [downs0[1] + 5, SETTINGS_ROW.network]], easeInOut),
    keys(f, [[downs1[0], 0], [downs1[0] + 5, NETWORK_ROW.home]], easeInOut),
    keys(f, [[downIp, 0], [downIp + 5, WIFI_ROW.ip]], easeInOut),
  ];
  const presses: Press[] = [
    ...downs0.map((t) => ({btn: 'down' as const, f: t})),
    {btn: 'ok', f: okNet},
    ...downs1.map((t) => ({btn: 'down' as const, f: t})),
    {btn: 'ok', f: okHome},
    {btn: 'down', f: downIp},
  ];
  const halo = tween(f, downIp + 6, 8);

  // wired case: the remote steps aside for the Ethernet mini panel
  const card = sp(f, tWire - 2, {damping: 20});
  const remoteOut = tween(f, tWire - 8, 12);
  const ring = tween(f, tEth + 4, 8);

  // the address flies from its row to the top-left corner
  const r = rowRect(WIFI_ROW.ip);
  const fromX = TV.x + (r[0] + (isEn ? r[2] - 30 : 30)) * K; // the card's anchored edge (left in Arabic, right in English) starts 30 px inside the row
  const fromY = TV.y + (r[1] + 30) * K;
  const fly = tween(f, tDigits + 6, 26, easeInOut);
  const noteX = mix(fromX, IP_NOTE_HOME.x, fly);
  const noteY = mix(fromY, IP_NOTE_HOME.y, fly) - Math.sin(fly * Math.PI) * 60;

  return (
    <div style={{position: 'absolute', inset: 0}}>
      <StepHeader step={4} />
      <TvStepStage panels={[P_SETTINGS, P_NETWORK, P_WIFI]} panel={panel} focus={focus} open={open} presses={presses} remoteOpacity={1 - remoteOut}>
        <RowHalo rect={r} t={halo} />
      </TvStepStage>
      <Crumbs
        items={[
          {ar: t('الإعدادات'), en: 'Settings', at: tSettings - 4},
          {ar: t('الشبكة والإنترنت'), en: 'Network & Internet', at: okNet},
          {ar: t('شبكتك'), en: 'HomeWiFi', at: okHome},
          {ar: t('عنوان IP'), en: 'IP address', at: downIp + 4},
        ]}
      />
      <NoteChip x={sx(60)} y={sy(236)} t={sp(f, tWifi - 2, {damping: 20})} icon="wifi" en="Wi-Fi">
        {t('على الواي فاي')}
      </NoteChip>
      <NoteChip x={sx(60)} y={sy(392)} t={sp(f, tRec - 2, {damping: 20})} icon="pencil">
        {t('اكتبه على ورقة: أرقام بينها نقاط')}
      </NoteChip>
      {card > 0.001 ? <EthernetCard p={card} ring={ring} /> : null}
      {f >= tDigits + 6 && fly < 1 ? <IpNote x={noteX} y={noteY} t={clamp(fly * 1.2 + 0.2)} glow={1 - fly} /> : null}
    </div>
  );
};
