/**
 * Work Desk — a separate environment for work-profile apps.
 *
 * A retro workstation: an amber CRT, a floppy drive, and a rack of disks
 * (one per work app). Apps aren't tapped open — you slide a disk up into
 * the drive to load it. A physical-style toggle at the top slides you
 * back to the personal launcher.
 *
 * Backend:
 *   GET  /api/profiles                → { has_work, work_paused }
 *   GET  /api/apps?profile=work       → work-profile apps
 *   POST /api/launch {package, profile:'work'}
 *   POST /api/profiles/work/resume    → unpause the work profile
 */

const ENV_KEY    = 'vos-env';          // 'work' while the desk is the active environment
const USAGE_KEY  = 'vos-work-usage';   // { pkg: launchCount } — most-used disks first
const DISK_COLORS = ['#1b2a4a', '#2b2b2b', '#5a1e1e', '#1f4030', '#3d3020', '#2a2346'];

function load(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ }
}

export function wantsWorkDesk() { return load(ENV_KEY, null) === 'work'; }

export function initWorkDesk({ post, escHtml, onExit }) {
  const desk   = document.getElementById('work-desk');
  const screen = document.getElementById('wd-screen');
  const log    = document.getElementById('wd-log');
  const clock  = document.getElementById('wd-clock');
  const drive  = document.getElementById('wd-drive');
  const rack   = document.getElementById('wd-rack');
  const sw     = document.getElementById('wd-switch');
  const knob   = document.getElementById('wd-knob');

  let apps = [];
  let profile = 'work';       // 'work', or 'personal' when the device has no work profile
  let clockTimer = null;
  let busy = false;

  // ── CRT log ────────────────────────────────────────────────────
  function print(text, cls = '') {
    const line = document.createElement('div');
    line.className = 'wd-line ' + cls;
    line.textContent = text;
    log.appendChild(line);
    while (log.children.length > 9) log.firstChild.remove();
    return line;
  }

  function tickClock() {
    const d = new Date();
    clock.textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  // ── Disks ──────────────────────────────────────────────────────
  function colorFor(pkg) {
    let h = 0;
    for (const c of pkg) h = (h * 31 + c.charCodeAt(0)) | 0;
    return DISK_COLORS[Math.abs(h) % DISK_COLORS.length];
  }

  function renderRack() {
    const usage = load(USAGE_KEY, {});
    const sorted = [...apps].sort((a, b) =>
      (usage[b.package] || 0) - (usage[a.package] || 0) || a.name.localeCompare(b.name));
    rack.innerHTML = '';
    sorted.forEach(app => rack.appendChild(makeDisk(app)));
  }

  function makeDisk(app) {
    const el = document.createElement('div');
    el.className = 'wd-disk' + (app.special ? ' wd-disk-special' : '');
    el.style.setProperty('--disk', app.special ? '#7a5a00' : colorFor(app.package));
    el.innerHTML = `
      <div class="wd-shutter"></div>
      <div class="wd-label">
        ${app.icon ? `<img src="data:image/jpeg;base64,${app.icon}" alt="">` : `<span class="wd-glyph">${app.glyph || '▣'}</span>`}
        <span>${escHtml(app.name)}</span>
      </div>`;
    attachDrag(el, app);
    return el;
  }

  // ── Drag a disk into the drive ─────────────────────────────────
  // The rack scrolls horizontally (touch-action: pan-x); an upward drag lifts
  // the disk out of the rack instead.
  function attachDrag(el, app) {
    let startX = 0, startY = 0, ghost = null, offX = 0, offY = 0, pid = null;

    el.addEventListener('pointerdown', e => {
      if (busy) return;
      pid = e.pointerId; startX = e.clientX; startY = e.clientY;
    });

    el.addEventListener('pointermove', e => {
      if (e.pointerId !== pid) return;
      const dx = e.clientX - startX, dy = e.clientY - startY;
      if (!ghost) {
        if (dy < -10 && Math.abs(dy) > Math.abs(dx)) {
          const r = el.getBoundingClientRect();
          offX = startX - r.left; offY = startY - r.top;
          ghost = el.cloneNode(true);
          ghost.classList.add('wd-ghost');
          ghost.style.width = r.width + 'px';
          document.body.appendChild(ghost);
          el.classList.add('wd-lifted');
          try { el.setPointerCapture(pid); } catch { /* ignore */ }
          if (navigator.vibrate) navigator.vibrate(8);
        } else return;
      }
      ghost.style.transform = `translate(${e.clientX - offX}px, ${e.clientY - offY}px) rotate(${Math.max(-8, Math.min(8, dx / 12))}deg)`;
      drive.classList.toggle('hot', overDrive(e.clientX, e.clientY));
    });

    const end = e => {
      if (e.pointerId !== pid) return;
      pid = null;
      if (!ghost) return;
      const g = ghost; ghost = null;
      const hit = e.type === 'pointerup' && overDrive(e.clientX, e.clientY);
      drive.classList.remove('hot');
      if (hit) {
        insertDisk(g, el, app);
      } else {
        // Snap back into the rack
        const r = el.getBoundingClientRect();
        g.style.transition = 'transform .25s ease';
        g.style.transform = `translate(${r.left}px, ${r.top}px)`;
        setTimeout(() => { g.remove(); el.classList.remove('wd-lifted'); }, 250);
      }
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
  }

  function overDrive(x, y) {
    const r = drive.getBoundingClientRect();
    const pad = 40;   // generous target — it's a thumb, not a mouse
    return x > r.left - pad && x < r.right + pad && y > r.top - pad * 2 && y < r.bottom + pad;
  }

  function insertDisk(ghost, el, app) {
    busy = true;
    const slot = drive.querySelector('.wd-slot').getBoundingClientRect();
    const gw = ghost.offsetWidth;
    ghost.style.transition = 'transform .22s ease-in, clip-path .3s ease-in .18s, opacity .3s .3s';
    ghost.style.transform = `translate(${slot.left + slot.width / 2 - gw / 2}px, ${slot.top - ghost.offsetHeight + 6}px)`;
    requestAnimationFrame(() => setTimeout(() => {
      ghost.style.transform += ' translateY(100%)';
      ghost.style.clipPath = 'inset(0 0 100% 0)';
    }, 200));
    drive.classList.add('loading');
    if (navigator.vibrate) navigator.vibrate([10, 40, 10]);

    if (app.special === 'resume') { resumeWork(ghost, el); return; }

    const usage = load(USAGE_KEY, {});
    usage[app.package] = (usage[app.package] || 0) + 1;
    save(USAGE_KEY, usage);

    print(`A:\\> RUN ${app.name.toUpperCase().replace(/\s+/g, '_')}`);
    const bar = print('[          ]', 'wd-bar');
    let n = 0;
    const fill = setInterval(() => {
      n = Math.min(10, n + 2);
      bar.textContent = '[' + '█'.repeat(n) + ' '.repeat(10 - n) + ']';
      if (n >= 10) clearInterval(fill);
    }, 60);

    setTimeout(async () => {
      try {
        const res = await post('/api/launch', { package: app.package, profile });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'failed');
        print('OK.', 'wd-dim');
      } catch (err) {
        print(`ERROR: ${err.message}`, 'wd-err');
      }
      ejectDone(ghost, el);
    }, 420);
  }

  function ejectDone(ghost, el) {
    setTimeout(() => {
      ghost.remove();
      el.classList.remove('wd-lifted');
      drive.classList.remove('loading');
      busy = false;
    }, 600);
  }

  async function resumeWork(ghost, el) {
    print('A:\\> RESUME WORK PROFILE');
    try {
      await post('/api/profiles/work/resume', {});
    } catch { /* fall through to re-check */ }
    // Android may show its own confirmation; poll briefly for the result.
    for (let i = 0; i < 10; i++) {
      await new Promise(r => setTimeout(r, 500));
      const p = await fetch('/api/profiles').then(r => r.json()).catch(() => null);
      if (p && !p.work_paused) { ejectDone(ghost, el); boot(); return; }
    }
    print('STILL PAUSED. CONFIRM ON DEVICE.', 'wd-err');
    ejectDone(ghost, el);
  }

  // ── Boot sequence ──────────────────────────────────────────────
  async function boot() {
    log.innerHTML = '';
    print('BALTEK WORKSTATION  v1.0', 'wd-bright');
    print('MEMORY TEST ........ OK', 'wd-dim');
    rack.innerHTML = '';

    const p = await fetch('/api/profiles').then(r => r.json()).catch(() => ({ has_work: false }));
    profile = p.has_work ? 'work' : 'personal';

    if (p.has_work && p.work_paused) {
      print('WORK PROFILE IS PAUSED.', 'wd-err');
      print('SLIDE THE KEY DISK INTO DRIVE A:');
      apps = [{ package: '__resume', name: 'Resume Work', glyph: '⚿', special: 'resume' }];
      rack.appendChild(makeDisk(apps[0]));
      return;
    }
    if (!p.has_work) {
      print('NO WORK PROFILE FOUND.', 'wd-err');
      print('MOUNTING PERSONAL DISKS INSTEAD.', 'wd-dim');
    }

    try {
      apps = await fetch(`/api/apps?profile=${profile}`).then(r => r.json());
    } catch { apps = []; }
    print(`${apps.length} DISK${apps.length === 1 ? '' : 'S'} IN RACK.`, 'wd-dim');
    print('SLIDE A DISK UP INTO DRIVE A: TO RUN.');
    print('A:\\> _', 'wd-cursor');
    renderRack();
  }

  // ── Environment switch (slide left → personal) ─────────────────
  {
    let pid = null, startX = 0, travel = 0, x = 0;
    const setX = v => { x = v; knob.style.transform = `translateX(${v}px)`; };

    knob.addEventListener('pointerdown', e => {
      pid = e.pointerId; startX = e.clientX;
      travel = sw.clientWidth - knob.offsetWidth - 6;
      knob.style.transition = 'none';
      try { knob.setPointerCapture(pid); } catch { /* ignore */ }
    });
    knob.addEventListener('pointermove', e => {
      if (e.pointerId !== pid) return;
      setX(Math.max(-travel, Math.min(0, e.clientX - startX)));
    });
    const release = e => {
      if (e.pointerId !== pid) return;
      pid = null;
      knob.style.transition = '';
      if (x < -travel * 0.6) {
        setX(-travel);
        if (navigator.vibrate) navigator.vibrate(12);
        save(ENV_KEY, 'personal');
        setTimeout(onExit, 180);
      } else setX(0);
    };
    knob.addEventListener('pointerup', release);
    knob.addEventListener('pointercancel', release);
  }

  return {
    element: desk,
    open() {
      save(ENV_KEY, 'work');
      knob.style.transform = '';
      screen.classList.remove('wd-poweron');
      void screen.offsetWidth;            // restart power-on animation
      screen.classList.add('wd-poweron');
      tickClock();
      clearInterval(clockTimer);
      clockTimer = setInterval(tickClock, 15000);
      boot();
    },
    close() {
      clearInterval(clockTimer);
      document.querySelectorAll('.wd-ghost').forEach(g => g.remove());
      drive.classList.remove('hot', 'loading');
      busy = false;
    },
  };
}
