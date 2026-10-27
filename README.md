Relax update v2
Overwrite these paths in `lava295/first-app` then push `main`:
```
app/src/main/AndroidManifest.xml
app/src/main/java/app/relax/MainActivity.kt
app/src/main/java/app/relax/Engine.kt
app/src/main/java/app/relax/Alerts.kt
app/src/main/res/drawable/ic_launcher_foreground.png
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
app/src/main/res/mipmap-*/ic_launcher.png
app/src/main/res/mipmap-*/ic_launcher_round.png
app/src/main/res/values/colors.xml
```
What's included
Icon — your attached photo (man in papasan chair), adaptive + all densities
Notifications fixed — only default preference + time window + strong filter; never applied/seen; permission requested on Alerts tab; WorkManager re-armed
Naukri — full browser headers; soft-fails on 406 with clear status (use mobile data)
Open → Applied — tapping Open marks applied, hides from Jobs forever, shows in Applied tab
Preferences — Save preference from adhoc search, Make default (★), delete custom prefs; alerts use default only
After install: Alerts tab → allow notifications → set Hourly or Daily → Battery unrestricted.
