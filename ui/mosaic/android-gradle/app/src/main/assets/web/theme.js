/**
 * Looks and wallpapers.
 *
 *   classic      solid accent tiles on black (Windows Phone 8)
 *   glass        frosted, raised tiles floating over a wallpaper (Windows 10 Mobile)
 *   see-through  black Start, the wallpaper shows only through the tiles and stays
 *                put while they scroll over it (Windows Phone 8.1 "start background")
 *
 * A wallpaper is a CSS background-image: a built-in gradient, or a photo the user
 * picks, cropped to the screen's shape so it lines up identically behind glass and
 * through see-through tiles.
 */
import { load, save, toast } from './store.js';

const KEY = 'mosaic-theme';
const PHOTO_KEY = 'mosaic-wallpaper-photo';

export const LOOKS = { classic: 'classic', glass: 'glass', seethru: 'see-through' };

export const WALLPAPERS = {
  aurora:   'radial-gradient(120% 70% at 15% 10%, #1f6feb 0, transparent 55%), radial-gradient(90% 60% at 90% 35%, #8b3dff 0, transparent 55%), radial-gradient(110% 70% at 40% 95%, #00b3c7 0, transparent 60%), #060a1a',
  dusk:     'radial-gradient(100% 60% at 80% 0%, #ff7a59 0, transparent 55%), radial-gradient(120% 70% at 0% 60%, #b83280 0, transparent 60%), linear-gradient(180deg, #2b1055 0%, #0b0b1e 100%)',
  ocean:    'radial-gradient(130% 70% at 50% 0%, #2bb6ff 0, transparent 60%), radial-gradient(100% 60% at 100% 100%, #00e1b4 0, transparent 55%), linear-gradient(180deg, #03304f 0%, #010a14 100%)',
  ember:    'radial-gradient(100% 60% at 20% 100%, #ff3d00 0, transparent 55%), radial-gradient(90% 60% at 90% 20%, #ffb300 0, transparent 50%), linear-gradient(160deg, #2a0a00 0%, #0d0200 100%)',
  forest:   'radial-gradient(110% 70% at 10% 15%, #2e9e5b 0, transparent 55%), radial-gradient(100% 70% at 100% 80%, #a4c400 0, transparent 50%), linear-gradient(180deg, #06210f 0%, #020a05 100%)',
  graphite: 'radial-gradient(120% 70% at 30% 10%, #5b6578 0, transparent 60%), radial-gradient(100% 60% at 90% 100%, #2c3240 0, transparent 60%), #0b0d12',
  prism:    'conic-gradient(from 210deg at 65% 35%, #0050ef, #6a00ff, #d80073, #fa6800, #f0a30a, #00aba9, #0050ef)',
};

export const theme = Object.assign({ look: 'classic', wallpaper: 'aurora', dim: 0.25, clarity: 0.65 }, load(KEY, {}));
let photo = null;
try { photo = localStorage.getItem(PHOTO_KEY); } catch { /* storage unavailable */ }

export const hasPhoto = () => !!photo;
export const photoUrl = () => photo;

function wallpaperImage() {
  if (theme.wallpaper === 'photo' && photo) return `url("${photo}")`;
  return WALLPAPERS[theme.wallpaper] || WALLPAPERS.aurora;
}

export function applyTheme() {
  const root = document.documentElement;
  root.dataset.look = theme.look;
  root.style.setProperty('--wp', wallpaperImage());
  root.style.setProperty('--wp-dim', String(theme.dim));
  // Glass clarity 0..1: how see-through the tiles are. Clearer glass carries less
  // colour and less frost, so the edge highlight and text shadow step up to keep
  // tiles defined and labels readable over the wallpaper.
  const t = theme.clarity;
  root.style.setProperty('--glass-tint', `${Math.round(80 - 72 * t)}%`);
  root.style.setProperty('--glass-shade', (0.35 * (1 - t)).toFixed(3));
  root.style.setProperty('--glass-blur', `${(6 + 16 * (1 - t)).toFixed(1)}px`);
  root.style.setProperty('--glass-edge', (0.16 + 0.18 * t).toFixed(3));
  root.style.setProperty('--glass-text', (0.3 + 0.35 * t).toFixed(3));
  save(KEY, theme);
}

export function setLook(look) { theme.look = look; applyTheme(); }
export function setWallpaper(name) { theme.wallpaper = name; applyTheme(); }
export function setDim(v) { theme.dim = Math.max(0, Math.min(0.8, v)); applyTheme(); }
export function setClarity(v) { theme.clarity = Math.max(0, Math.min(1, v)); applyTheme(); }

/**
 * Turn a picked photo into a wallpaper: crop it to the screen's shape (cover),
 * cap it near screen resolution, and keep it as a JPEG data URL.
 */
export async function usePhoto(file) {
  if (!file) return;
  const img = await new Promise((ok, fail) => {
    const url = URL.createObjectURL(file);
    const i = new Image();
    i.onload = () => { URL.revokeObjectURL(url); ok(i); };
    i.onerror = () => { URL.revokeObjectURL(url); fail(new Error('not an image')); };
    i.src = url;
  }).catch(() => null);
  if (!img) return toast('Couldn’t read that image');

  const scale = Math.min(2, window.devicePixelRatio || 1);
  const W = Math.round(Math.min(1080, innerWidth * scale)), H = Math.round(W * innerHeight / innerWidth);
  const c = document.createElement('canvas');
  c.width = W; c.height = H;
  const s = Math.max(W / img.width, H / img.height);
  const w = img.width * s, h = img.height * s;
  c.getContext('2d').drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
  const data = c.toDataURL('image/jpeg', 0.82);

  photo = data;
  try { localStorage.setItem(PHOTO_KEY, data); }
  catch { toast('That photo is too big to keep after a restart, but it’s on for now'); }
  if (theme.look === 'classic') theme.look = 'glass';     // a wallpaper means nothing on classic
  setWallpaper('photo');
}
