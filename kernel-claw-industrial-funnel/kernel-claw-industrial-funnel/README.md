# Kernel Claw Industrial Funnel

Single-page industrial landing funnel for AWS Amplify.

## Deploy on Amplify

1. Push this folder to a GitHub repository.
2. In AWS Amplify, choose **Host web app**.
3. Connect the repo.
4. Amplify should detect the included `amplify.yml`.
5. Build command: `npm run build`
6. Output directory: `dist`

## Lead Capture

This is static-friendly.

Set this environment variable in Amplify if you have a webhook/Formspree/Zapier endpoint:

```bash
VITE_LEAD_ENDPOINT=https://your-webhook-url
```

If no endpoint is set, the form opens a prefilled email to `sales@balteksystems.com`.

Change that email inside `src/App.jsx`.
