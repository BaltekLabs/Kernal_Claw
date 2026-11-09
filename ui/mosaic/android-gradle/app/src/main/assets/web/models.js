/**
 * Models the picker offers, per provider. `fast` is the cheap model the server
 * routes simple questions to (its FAST tier); `main` handles everything else.
 * Any provider also accepts a custom model id typed into the picker.
 */
export const PROVIDERS = {
  anthropic: {
    label: 'claude', needsKey: true,
    main: [
      { id: 'claude-opus-5-5',   name: 'Opus 5.5',   note: 'Most capable everyday model. Recommended.' },
      { id: 'claude-sonnet-5-5', name: 'Sonnet 5.5', note: 'Fast and capable, lower cost.' },
      { id: 'claude-haiku-5-5',  name: 'Haiku 5.5',  note: 'Quickest and cheapest.' },
      { id: 'claude-fable-5-1',  name: 'Fable 5.1',  note: 'Anthropic’s most capable. Slower and pricier.' },
    ],
    fast: [
      { id: 'claude-haiku-5-5',  name: 'Haiku 5.5' },
      { id: 'claude-sonnet-5-5', name: 'Sonnet 5.5' },
    ],
  },
  openai: {
    label: 'openai', needsKey: true,
    main: [
      { id: 'gpt-4o',      name: 'GPT-4o',      note: 'OpenAI’s general model.' },
      { id: 'gpt-4o-mini', name: 'GPT-4o mini', note: 'Cheaper and faster.' },
    ],
    fast: [{ id: 'gpt-4o-mini', name: 'GPT-4o mini' }],
  },
  grok: {
    label: 'grok', needsKey: true,
    main: [
      { id: 'grok-4.20-0309-non-reasoning', name: 'Grok 4.20',      note: 'xAI’s general model.' },
      { id: 'grok-4-1-fast-non-reasoning',  name: 'Grok 4.1 fast',  note: 'Cheaper and faster.' },
    ],
    fast: [{ id: 'grok-4-1-fast-non-reasoning', name: 'Grok 4.1 fast' }],
  },
  ollama: {
    label: 'ollama', needsKey: false,
    main: [{ id: 'mistral:latest', name: 'mistral', note: 'Runs on your own Ollama server.' }],
    fast: [],
  },
};

/** Friendly name for a model id ("claude-opus-5-5" → "Opus 5.5"). */
export function modelName(id) {
  for (const p of Object.values(PROVIDERS)) {
    const m = [...p.main, ...p.fast].find(x => x.id === id);
    if (m) return m.name;
  }
  return id || '—';
}
