# CASA Suite native apps

Wrapper apps for CASA Suite (https://kinetic-casa-suite.apper.so) so installed apps open directly.

- `android/` - Kotlin WebView app with a JS bridge (`window.CasaNative`).
- `windows/` - .NET 8 + WebView2 app; reads Start Menu apps and launches them.
- `shared/test.html` - built-in test page (default start page for test builds).
- Builds run in GitHub Actions: Actions tab -> latest run -> Artifacts.

## Casana

`casana/` holds the Casana apps (https://apex-casa-workdesk.apper.so), built by the same workflow:

- `casana/android/` - WebView app, package `com.casaaudit.casana` (CASA Suite opens it by this package).
  Attach files, save exported files, and open Casana links from other apps.
- `casana/windows/` - WebView2 app; Start menu name `Casana` (CASA Suite's Windows app opens it by this name).
  The installer registers `casana://` so CASA Suite in a normal browser can open it too.

Download links (same release as CASA Suite):

- https://github.com/caai-dev/casa-suite-apps/releases/latest/download/Casana.apk
- https://github.com/caai-dev/casa-suite-apps/releases/latest/download/Casana-Setup.exe
