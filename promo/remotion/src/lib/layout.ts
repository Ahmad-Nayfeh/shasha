// Shared stage layout (1920x1080). RTL: the right side is the reading start.
export const FEATURE_TV = {x: 150, y: 214, w: 1088}; // screen rect of the TV in the feature tour
export const tvH = (w: number) => (w * 9) / 16;

/** Map a point in a TV's screen space (1920x1080) to stage px. */
export const toStage = (tv: {x: number; y: number; w: number}, sx: number, sy: number): [number, number] => [
  tv.x + (sx * tv.w) / 1920,
  tv.y + (sy * tv.w) / 1920,
];

/** Map a relative manifest rect to stage px for a TV. */
export const rectToStage = (tv: {x: number; y: number; w: number}, r: [number, number, number, number]): [number, number, number, number] => [
  tv.x + r[0] * tv.w,
  tv.y + r[1] * tvH(tv.w),
  r[2] * tv.w,
  r[3] * tvH(tv.w),
];

export const RIGHT_COL = {x: 1330, w: 500, right: 1830};
