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

/** Usage key — the same package can be installed in both profiles. */
function uk(app) { return `${app.profile || 'work'}:${app.package}`; }

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
  const input  = document.getElementById('wd-input');
  const ps     = document.getElementById('wd-ps');
  const matchesEl = document.getElementById('wd-matches');

  let apps = [];              // disks in the rack (work apps, or personal if no work profile)
  let personalApps = [];      // personal apps, searchable from the prompt when a work profile exists
  let hasWork = false;
  let profile = 'work';       // the rack's profile: 'work', or 'personal' when the device has no work profile
  let clockTimer = null;
  let busy = false;
  let mode = 'apps';          // 'apps' (DOS prompt) | 'shell' (Termux)
  let cwd  = '~';
  let shellBusy = false;

  // ── CRT log ────────────────────────────────────────────────────
  function print(text, cls = '') {
    const line = document.createElement('div');
    line.className = 'wd-line ' + cls;
    line.textContent = text;
    log.appendChild(line);
    while (log.children.length > 300) log.firstChild.remove();
    log.scrollTop = log.scrollHeight;
    return line;
  }

  function prompt() { return mode === 'shell' ? `${cwd} $` : 'A:\\>'; }
  function setPrompt() {
    ps.textContent = prompt();
    ps.parentElement.classList.toggle('wd-shell', mode === 'shell');   // shell is case-sensitive
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
      (usage[uk(b)] || 0) - (usage[uk(a)] || 0) || a.name.localeCompare(b.name));
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

    print(`A:\\> RUN ${app.name.toUpperCase().replace(/\s+/g, '_')}`);
    runApp(app).then(() => ejectDone(ghost, el));
  }

  /** Loading bar + launch. Shared by the drive and the typed prompt. */
  function runApp(app) {
    const usage = load(USAGE_KEY, {});
    usage[uk(app)] = (usage[uk(app)] || 0) + 1;
    save(USAGE_KEY, usage);

    const bar = print('[          ]', 'wd-bar');
    let n = 0;
    const fill = setInterval(() => {
      n = Math.min(10, n + 2);
      bar.textContent = '[' + '█'.repeat(n) + ' '.repeat(10 - n) + ']';
      if (n >= 10) clearInterval(fill);
    }, 60);

    return new Promise(resolve => setTimeout(async () => {
      try {
        const res = await post('/api/launch', { package: app.package, profile: app.profile || profile });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).error || 'failed');
        print('OK.', 'wd-dim');
      } catch (err) {
        print(`ERROR: ${err.message}`, 'wd-err');
      }
      resolve();
    }, 420));
  }

  function ejectDone(ghost, el) {
    setTimeout(() => {
      ghost?.remove();
      el?.classList.remove('wd-lifted');
      drive.classList.remove('loading');
      busy = false;
    }, 600);
  }

  // ── Typed prompt ───────────────────────────────────────────────
  // App mode: type to search disks, tap a match or press Enter for the top one.
  // `termux` switches to a Termux shell (via the bridge); `exit` comes back.
  const HELP = [
    'COMMANDS:',
    '  <name>       FIND AND RUN AN APP ([W]ORK / [P]ERSONAL)',
    '  DIR          LIST ALL APPS',
    '  $ <cmd>      RUN ONE TERMUX COMMAND',
    '  TERMUX       OPEN A TERMUX SHELL (EXIT TO LEAVE)',
    '  CLS          CLEAR SCREEN',
  ];

  function findApps(q) {
    q = q.toLowerCase().trim();
    if (!q) return [];
    const usage = load(USAGE_KEY, {});
    return [...apps, ...personalApps]
      .filter(a => !a.special && a.name.toLowerCase().includes(q))
      .sort((a, b) =>
        (b.name.toLowerCase().startsWith(q) - a.name.toLowerCase().startsWith(q)) ||
        ((usage[uk(b)] || 0) - (usage[uk(a)] || 0)) ||
        a.name.localeCompare(b.name))
      .slice(0, 5);
  }

  function renderMatches() {
    matchesEl.innerHTML = '';
    const v = input.value;
    if (mode !== 'apps' || v.trim().startsWith('$')) return;
    const q = v.replace(/^\s*(run|open)\s+/i, '');
    findApps(q).forEach((app, i) => {
      const row = document.createElement('div');
      row.className = 'wd-match' + (i === 0 ? ' wd-match-top' : '');
      const tag = hasWork ? (app.profile === 'personal' ? '[P] ' : '[W] ') : '';
      row.textContent = `${i === 0 ? '▶' : ' '} ${tag}${app.name.toUpperCase()}`;
      // pointerup fires before the input blurs and the keyboard reflows the layout
      row.addEventListener('pointerup', e => { e.preventDefault(); typedLaunch(app); });
      matchesEl.appendChild(row);
    });
  }

  function typedLaunch(app) {
    if (busy) return;
    busy = true;
    print(`A:\\> RUN ${app.name.toUpperCase().replace(/\s+/g, '_')}`);
    input.value = ''; matchesEl.innerHTML = '';
    input.blur();
    drive.classList.add('loading');
    runApp(app).then(() => ejectDone());
  }

  function submit() {
    const raw = input.value;
    const v = raw.trim();
    input.value = '';
    matchesEl.innerHTML = '';
    if (!v) return;

    if (mode === 'shell') {
      if (/^(exit|logout)$/i.test(v)) {
        print(`${prompt()} ${v}`);
        mode = 'apps'; setPrompt();
        print('LEFT TERMUX.', 'wd-dim');
        return;
      }
      if (/^(clear|cls)$/i.test(v)) { log.innerHTML = ''; return; }
      runShell(v, true);
      return;
    }

    const lower = v.toLowerCase();
    if (lower === 'cls' || lower === 'clear') { log.innerHTML = ''; return; }
    print(`A:\\> ${v}`);
    if (lower === 'help' || lower === '?') { HELP.forEach(l => print(l, 'wd-dim')); return; }
    if (lower === 'dir' || lower === 'ls' || lower === 'apps') {
      const disks = apps.filter(a => !a.special);
      if (hasWork) print('A: WORK', 'wd-bright');
      disks.forEach(a => print(`  ${a.name.toUpperCase()}`, 'wd-dim'));
      if (hasWork) {
        print('C: PERSONAL', 'wd-bright');
        personalApps.forEach(a => print(`  ${a.name.toUpperCase()}`, 'wd-dim'));
      }
      print(`${disks.length + personalApps.length} APP(S).`, 'wd-dim');
      return;
    }
    if (lower === 'termux' || lower === 'sh' || lower === 'shell') {
      mode = 'shell'; setPrompt();
      print('TERMUX SHELL. TYPE EXIT TO RETURN.', 'wd-dim');
      runShell('echo "$(whoami)@termux"', false);
      return;
    }
    if (v.startsWith('$')) { runShell(v.slice(1).trim(), false); return; }

    const match = findApps(v.replace(/^(run|open)\s+/i, ''))[0];
    if (match) { typedLaunch(match); return; }
    print('BAD COMMAND OR FILE NAME', 'wd-err');
  }

  async function runShell(cmd, echo) {
    if (!cmd) return;
    if (shellBusy) { print('BUSY — WAIT FOR THE LAST COMMAND.', 'wd-err'); return; }
    if (echo) print(`${prompt()} ${cmd}`);
    shellBusy = true;
    input.disabled = true;
    const spinner = print('…', 'wd-dim wd-cursor');
    try {
      const res = await fetch('/api/shell', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-VoiceOS-Terminal': '1' },
        body: JSON.stringify({ command: cmd, cwd }),
      });
      const data = await res.json();
      spinner.remove();
      if (data.offline) {
        print('TERMUX BRIDGE OFFLINE.', 'wd-err');
        print('IN TERMUX, RUN ONCE:', 'wd-dim');
        print('  termux-setup-storage');
        print('  curl -s localhost:8741/api/bridge/setup > ~/voiceos-bridge.sh');
        print('THEN, EACH TIME:', 'wd-dim');
        print('  bash ~/voiceos-bridge.sh &');
        if (mode === 'shell') { mode = 'apps'; setPrompt(); }
      } else {
        if (data.output) data.output.split('\n').forEach(l => print(l, 'wd-out'));
        cwd = data.cwd || cwd;
        setPrompt();
      }
    } catch (err) {
      spinner.remove();
      print(`ERROR: ${err.message}`, 'wd-err');
    }
    shellBusy = false;
    input.disabled = false;
    if (mode === 'shell') input.focus();
  }

  input.addEventListener('input', renderMatches);
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); submit(); }
  });
  // Tapping the screen focuses the prompt (but not while scrolling the log)
  screen.addEventListener('click', e => {
    if (!matchesEl.contains(e.target)) input.focus();
  });

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
    mode = 'apps'; setPrompt();
    input.value = ''; matchesEl.innerHTML = '';
    print('BALTEK WORKSTATION  v1.0', 'wd-bright');
    print('MEMORY TEST ........ OK', 'wd-dim');
    rack.innerHTML = '';

    const p = await fetch('/api/profiles').then(r => r.json()).catch(() => ({ has_work: false }));
    profile = p.has_work ? 'work' : 'personal';
    hasWork = !!p.has_work;

    // Personal apps are always reachable from the prompt; the rack holds work disks.
    const fetchApps = prof => fetch(prof === 'work' ? '/api/apps?profile=work' : '/api/apps')
      .then(r => r.json())
      .then(list => list.map(a => ({ ...a, profile: prof })))
      .catch(() => []);
    personalApps = hasWork ? await fetchApps('personal') : [];

    if (p.has_work && p.work_paused) {
      print('WORK PROFILE IS PAUSED.', 'wd-err');
      print('SLIDE THE KEY DISK INTO DRIVE A:');
      print(`${personalApps.length} PERSONAL APPS ON C: — TYPE A NAME.`, 'wd-dim');
      apps = [{ package: '__resume', name: 'Resume Work', glyph: '⚿', special: 'resume' }];
      rack.appendChild(makeDisk(apps[0]));
      return;
    }
    if (!p.has_work) {
      print('NO WORK PROFILE FOUND.', 'wd-err');
      print('MOUNTING PERSONAL DISKS INSTEAD.', 'wd-dim');
    }

    apps = await fetchApps(profile);
    print(`${apps.length} DISK${apps.length === 1 ? '' : 'S'} IN RACK.`, 'wd-dim');
    if (hasWork) print(`${personalApps.length} PERSONAL APPS ON C:.`, 'wd-dim');
    print('SLIDE A DISK INTO DRIVE A:, OR TYPE A NAME.');
    print('TYPE HELP FOR COMMANDS.', 'wd-dim');
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
      input.blur();
      document.querySelectorAll('.wd-ghost').forEach(g => g.remove());
      drive.classList.remove('hot', 'loading');
      busy = false;
    },
  };
}
