# CASA Suite native apps

Wrapper apps for CASA Suite (https://casa-suite-protocol.apper.so) so installed apps open directly.

- `android/` - Kotlin WebView app with a JS bridge (`window.CasaNative`).
- `windows/` - .NET 8 + WebView2 app; reads Start Menu apps and launches them.
- `shared/test.html` - built-in test page (default start page for test builds).
- Builds run in GitHub Actions: Actions tab -> latest run -> Artifacts.
