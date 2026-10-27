Naukri permanent fix
Replace only:
app/src/main/java/app/relax/Engine.kt
Why 406 happened
Naukri's WEB client (`src=jobsearchDesk`) triggers reCAPTCHA on datacenter /
VPN / repeated bot-like TLS fingerprints. Status 406 = "recaptcha required".
What this fix does
Path A (primary): call the same API with `src=jobsearchAndroid` + okhttp UA
— this is the official mobile-app traffic shape and skips web recaptcha on most networks.
Path B: fall back to desktop web headers if Android path fails.
Path C: HTML page scrape last resort.
Cookie jar: warm session on naukri.com so `_t_ds` / `J` cookies stick.
Backoff: exponential delay on 406/429 before switching path.
Stops burning queries after consecutive blocks.
Verified live: `src=jobsearchAndroid` returned HTTP 200 + jobDetails from this environment.
Push main → rebuild APK.
