// Illustrative values shown in the vector parts of the video, kept in one place and in line with the real app:
// - TV_IP: the README's example address (the app's own pairing-screen captures happen to show 192.168.1.23);
// - the phone pairs over the app's local server, port 8765 (RemoteSettings.DEFAULT_PORT);
// - releases are tagged v<versionName>-<versionCode> with the asset shasha-<versionName>-<versionCode>.apk
//   (.github/workflows/android.yml); versionName 1.0.0.
export const TV_IP = '192.168.1.50';
export const ADB_SERIAL = `${TV_IP}:5555`;
export const PAIR_PORT = 8765;
export const PAIR_TOKEN = 'Xq3vB9kLm2PzT7wYc4NfR8sHd1JgE6uA'; // the token in the app's pairing captures
export const PAIR_URL = `http://${TV_IP}:${PAIR_PORT}/?t=${PAIR_TOKEN}`; // encoded in data/qr.json
export const PAIR_URL_SHORT = `${TV_IP}:${PAIR_PORT}/?t=…`;
export const VERSION_NAME = '1.0.0';
export const VERSION_CODE = 1042;
export const RELEASE_TAG = `v${VERSION_NAME}-${VERSION_CODE}`;
export const APK_NAME = `shasha-${VERSION_NAME}-${VERSION_CODE}.apk`;
export const APK_SIZE = '18.0 MB'; // as in the app's own update captures (updates_downloading: «من 18.0 ميغابايت»)
