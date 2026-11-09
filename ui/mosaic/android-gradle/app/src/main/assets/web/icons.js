// Line glyphs in the Metro spirit: single-weight strokes, no fills.
const P = {
  clock:    '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.5 2"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15"/><path d="M3.5 9.5h17M8 3v4M16 3v4"/>',
  message:  '<path d="M4 5h16v11H9l-5 4z"/>',
  phone:    '<path d="M6.5 3.5h3l1.5 4-2 1.5a11 11 0 0 0 6 6l1.5-2 4 1.5v3a2 2 0 0 1-2 2A16 16 0 0 1 4.5 5.5a2 2 0 0 1 2-2z"/>',
  people:   '<circle cx="9" cy="8.5" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><circle cx="17" cy="9.5" r="2.6"/><path d="M16 14.2a5 5 0 0 1 6 5.3"/>',
  tasks:    '<rect x="4" y="4" width="16" height="16"/><path d="M8 12l3 3 5-6"/>',
  bell:     '<path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20.5h4"/>',
  shield:   '<path d="M12 3l7.5 3v5.5c0 4.6-3.2 8-7.5 9.5-4.3-1.5-7.5-4.9-7.5-9.5V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/>',
  battery:  '<rect x="3" y="7.5" width="16" height="9"/><path d="M21 10.5v3"/>',
  work:     '<rect x="3" y="7.5" width="18" height="12"/><path d="M9 7.5V5h6v2.5M3 12.5h18"/>',
  gear:     '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
  mic:      '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21"/>',
  send:     '<path d="M4 12l16-8-6 16-2.5-6.5z"/>',
  plus:     '<path d="M12 5v14M5 12h14"/>',
  check:    '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  close:    '<path d="M6 6l12 12M18 6L6 18"/>',
  unpin:    '<path d="M9 4h6l-1 5 3 3H7l3-3zM12 12v8M4 4l16 16"/>',
  pin:      '<path d="M9 4h6l-1 5 3 3H7l3-3zM12 12v8"/>',
  resize:   '<path d="M7 17L17 7M10 7h7v7"/>',
  refresh:  '<path d="M19 12a7 7 0 1 1-2-4.9M19 4v4h-4"/>',
  sms:      '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 10h8"/>',
  map:      '<path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.3"/>',
  sun:      '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
  palette:  '<path d="M12 3.5a8.5 8.5 0 1 0 0 17c1.4 0 1.8-1 1.3-2-.6-1.2.1-2.5 1.5-2.5H17a3.5 3.5 0 0 0 3.5-3.5c0-5-3.8-9-8.5-9z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10.5" cy="7.5" r="1"/><circle cx="15" cy="8" r="1"/>',
  keyboard: '<rect x="2.5" y="6.5" width="19" height="11"/><path d="M6 10h.01M9.5 10h.01M13 10h.01M16.5 10h.01M7 14h10"/>',
  trash:    '<path d="M5 7h14M10 7V4.5h4V7M6.5 7l1 13h9l1-13"/>',
  apps:     '<rect x="4" y="4" width="6.5" height="6.5"/><rect x="13.5" y="4" width="6.5" height="6.5"/><rect x="4" y="13.5" width="6.5" height="6.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5"/>',
  note:     '<path d="M5 3.5h10l4 4v13H5z"/><path d="M14.5 3.5v4.5H19M8.5 12h7M8.5 16h7"/>',
};

export function icon(name) {
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${P[name] || P.apps}</svg>`;
}
