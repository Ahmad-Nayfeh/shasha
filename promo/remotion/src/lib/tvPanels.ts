// Google TV settings panels (redrawn, English labels as on the TCL TV) used by the installation steps 2-4 and 6.
import {GtvPanel} from '../components/GoogleTv';
import {TV_IP} from './demo';

export const P_SETTINGS: GtvPanel = {
  title: 'Settings',
  items: [
    {label: 'Accounts & Sign In', icon: 'user'},
    {label: 'Apps', icon: 'apps'},
    {label: 'Network & Internet', icon: 'wifi'},
    {label: 'Display & Sound', icon: 'display'},
    {label: 'Remotes & Accessories', icon: 'remote'},
    {label: 'System', icon: 'system'},
    {label: 'Privacy', icon: 'privacy'},
    {label: 'Help & Feedback', icon: 'info'},
  ],
};
export const SETTINGS_ROW = {network: 2, system: 5};

export const P_SYSTEM = (dev: boolean): GtvPanel => ({
  title: 'System',
  items: [
    {label: 'About', icon: 'info'},
    {label: 'Date & time', icon: 'clock'},
    {label: 'Language', icon: 'globe'},
    {label: 'Storage', icon: 'storage'},
    {label: 'Power & energy', icon: 'power'},
    ...(dev ? [{label: 'Developer options', icon: 'code'}] : []),
    {label: 'Restart', icon: 'power'},
  ],
});
export const SYSTEM_ROW = {about: 0, developer: 5};

export const P_ABOUT: GtvPanel = {
  title: 'About',
  items: [
    {label: 'System update'},
    {label: 'Device name', sub: 'Living room TV'},
    {label: 'Status', sub: 'Network, serial numbers and other info'},
    {label: 'Legal information'},
    {label: 'Model', sub: 'Google TV'},
    {label: 'Android TV OS version', sub: '12'},
    {label: 'Android TV OS security patch level', sub: 'July 1, 2026'},
    {label: 'Android TV OS build', sub: 'STT1.230105.001'},
  ],
};
export const ABOUT_ROW = {build: 7};

export const P_DEVELOPER = (usb: number, net: number, wireless = false): GtvPanel => ({
  title: 'Developer options',
  items: [
    {label: 'Stay awake', toggle: 0},
    {label: 'Take bug report'},
    {label: 'USB debugging', sub: 'Debug mode when USB is connected', toggle: usb},
    wireless ? {label: 'Wireless debugging', sub: 'Debug mode when Wi-Fi is connected', toggle: net} : {label: 'Network debugging', sub: 'Debug over the local network', toggle: net},
    {label: 'Verify apps over USB', toggle: 1},
    {label: 'Select debug app'},
  ],
});
export const DEV_ROW = {usb: 2, net: 3};

export const P_NETWORK: GtvPanel = {
  title: 'Network & Internet',
  items: [
    {label: 'Wi-Fi', toggle: 1},
    {label: 'HomeWiFi', sub: 'Connected', icon: 'wifi'},
    {label: 'Neighbour-5G', icon: 'wifi'},
    {label: 'Add new network'},
    {label: 'Ethernet', sub: 'Not connected'},
  ],
};
export const NETWORK_ROW = {home: 1};

export const P_WIFI: GtvPanel = {
  title: 'HomeWiFi',
  items: [
    {label: 'Connected', sub: 'Excellent signal'},
    {label: 'IP address', sub: TV_IP},
    {label: 'Proxy settings', sub: 'None'},
    {label: 'IP settings', sub: 'DHCP'},
    {label: 'Forget network'},
  ],
};
export const WIFI_ROW = {ip: 1};

export const P_WIRELESS: GtvPanel = {
  title: 'Wireless debugging',
  items: [
    {label: 'Use wireless debugging', toggle: 1},
    {label: 'Device name', sub: 'Living room TV'},
    {label: 'IP address & Port', sub: `${TV_IP}:41234`},
    {label: 'Pair device with pairing code', sub: 'Pair new devices using a six-digit code'},
    {label: 'Paired devices'},
  ],
};
export const WIRELESS_ROW = {pair: 3};

/** Row geometry of the redrawn panel (GoogleTv.tsx): list top, row height, panel width (screen space). */
export const PANEL = {w: 700, top: 200, row: 92};
/** Screen-space rect of a row in the settings panel (for highlights drawn on the TV). */
export const rowRect = (i: number): [number, number, number, number] => [1920 - PANEL.w + 40, PANEL.top + i * PANEL.row, PANEL.w - 80, PANEL.row - 8];
