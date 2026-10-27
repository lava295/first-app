# Relax — personal job tracker (Android)

## Get the APK (no Android Studio needed)
1. Create a new **private** GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab -> "Build APK" runs automatically (or press "Run workflow").
3. When it turns green, open the run and download the **Relax-apk** artifact, unzip, and install `app-debug.apk`
   (allow "install unknown apps" for your browser/files app).

Local build alternative: install JDK 17 + Android SDK, then run `gradle assembleDebug`.

## After installing
- Alerts tab -> choose a schedule -> allow notifications.
- Settings -> Apps -> Relax -> Battery -> **Unrestricted** (otherwise Android may delay background checks).
