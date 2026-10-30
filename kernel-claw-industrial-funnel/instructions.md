Built it as a static-friendly React/Vite funnel for AWS Amplify.

Download: [kernel-claw-industrial-funnel.zip](sandbox:/mnt/data/kernel-claw-industrial-funnel.zip)

What’s included:

* `package.json`
* `amplify.yml`
* `index.html`
* `src/App.jsx`
* `src/main.jsx`
* `src/styles.css`
* `README.md`

The page positions it as:

> **Kernel Claw FieldOps — Industrial AI mobile command system**

Core funnel angle:

* Hardware + software package
* Field technician troubleshooting
* Controls / robotics / maintenance support
* Paid pilot CTA
* Lead capture form

The form works two ways:

1. Set this Amplify environment variable for webhook capture:

```bash
VITE_LEAD_ENDPOINT=https://your-webhook-url
```

2. If no endpoint is set, it falls back to opening a prefilled email to:

```bash
sales@balteksystems.com
```

You’ll probably want to change that email inside `src/App.jsx` if needed:

```js
const FALLBACK_EMAIL = 'sales@balteksystems.com';
```

Deploy path:

```bash
npm install
npm run build
```

Amplify should use the included `amplify.yml` and publish from `dist`.
