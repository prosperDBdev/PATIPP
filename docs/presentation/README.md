# PATIPP creator video

Open [PATIPP-video-script.pdf](PATIPP-video-script.pdf) for the three-page recording guide.
It includes a 3-4 minute first-person script, a 45-60 second version, and practical shot notes.
The script describes the challenge as prepared but not started, and explains that coding
checks and independence are self-reported after working in an IDE.

Edit [PATIPP-video-script.html](PATIPP-video-script.html), then regenerate from `frontend`:

```powershell
npm run pdf:video
```

The exporter uses installed Microsoft Edge through Playwright. On a machine with Chrome,
set `PLAYWRIGHT_CHANNEL=chrome` first. It exports an A4 PDF and rejects overflowing pages.

For the app demo, use your real account and actual progress. The browser layout checks
use isolated fixtures; those are verification tools, not demo progress to present as yours.

## Layout verification

With the frontend running on port 3001, run `npm run test:layout` from `frontend`.
This checks 1440px and 390px screens, provider fallback/configured states, the challenge
and selecting an assisted exercise to rebuild. Screenshots go into ignored
`artifacts/previews/`. Google Identity Services is stubbed for that check; it does not
prove a real Google sign-in. Test that separately on the deployed site after setting
`PATIPP_GOOGLE_CLIENT_ID` in Render and authorising the site's origin in Google Cloud.
