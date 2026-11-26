# Contributing to R4A

Thanks for wanting to help! Bug reports, ideas, translations, docs and code are all welcome.

## Questions and ideas

- **Questions** ("how do I…?"): use [Discussions](https://github.com/4404c/Rust4Android/discussions).
- **Bugs and feature requests**: open an [issue](https://github.com/4404c/Rust4Android/issues/new/choose) with the form.
- **Security problems**: report them privately, see [SECURITY.md](SECURITY.md).

## Before you write code

- **Small fixes** (a bug, a typo, a clearer message): just send a pull request.
- **Big changes** (a new feature, a new screen, a new dependency, a refactor): **open an issue first** so we can talk about it. That way nobody spends days on something that can't be merged.
- Issues labeled [`good first issue`](https://github.com/4404c/Rust4Android/labels/good%20first%20issue) are a good place to start, and [`help wanted`](https://github.com/4404c/Rust4Android/labels/help%20wanted) ones are where help is most needed.

## Building the app

You need:

- JDK 17
- Android SDK with platform 35 (Android Studio installs it for you)
- An arm64 Android phone (Android 8.0+) to try it. Emulators on x86 PCs can't run the toolchain.

```bash
git clone https://github.com/4404c/Rust4Android.git
cd Rust4Android/android
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest   # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. A few JVM tests use Unix paths and fail on Windows; CI runs them on Linux, where they must pass.

The app is Kotlin + Jetpack Compose (package `dev.rustdroid.ide`, folder `android/app`). The Rust toolchain that the app downloads is built separately by the `Build RustDroid Toolchain` workflow (`.github/workflows/main.yml`, a few hours on GitHub Actions). You don't need to build it to work on the app.

## Pull requests we accept

- Bug fixes, with a test when it's possible.
- Features that were agreed in an issue.
- Better texts and translations. The app is in Spanish (`res/values/`) and English (`res/values-en/`): add new strings to **both**.
- Docs and README improvements.

We'll probably say no to:

- changes that need new permissions or that weaken safe mode without a good reason;
- telemetry, ads or trackers;
- big rewrites or style-only changes that weren't discussed first;
- raising `targetSdk` above 28 (R4A needs it to run the toolchain from its data folder).

## Pull request checklist

- One topic per pull request, with a clear title.
- It builds and the tests pass (CI checks this on every PR).
- No API keys, passwords or personal data in code, logs or screenshots.
- Explain how you tested it: phone model and Android version, or "JVM tests only".

## Code of conduct

Be kind. Everyone who takes part follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By sending a contribution you agree that it is published under the [MIT License](LICENSE), like the rest of the code. The R4A name, logo and Ferrdroid are not part of that license: see [TRADEMARKS.md](TRADEMARKS.md).
