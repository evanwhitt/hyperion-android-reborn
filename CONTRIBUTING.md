# Contributing

Thanks for wanting to help out! Here's how to get going.

## Reporting a bug

Before opening an issue, please:

1. Search the [issues](https://github.com/evanwhitt/hyperion-android-reborn/issues) to see if it's already been reported.
2. Use the **Bug report** template and fill in as much as you can.
3. Include the details that matter most:
   - TV / device model and Android version
   - App version (check Settings or the installed version)
   - Whether it happens with the **Standard** or **Codec** capture method
   - Steps to reproduce
   - A logcat if you can get one (`adb logcat`)

## Requesting a feature

Use the **Feature request** template and describe what you want and why. A screenshot or sketch helps a lot.

## Building from source

Requirements: JDK 17 and an Android SDK (compileSdk 34, minSdk 21).

```sh
git clone https://github.com/evanwhitt/hyperion-android-reborn.git
cd hyperion-android-reborn
# make sure local.properties has your SDK path, e.g. sdk.dir=/path/to/android-sdk
./gradlew :app:assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests with:

```sh
./gradlew :app:testDebugUnitTest
```

## Submitting changes

1. Fork the repo and create a branch.
2. Make your changes.
3. Keep the diff small and focused - one feature/fix per pull request.
4. Add a test when you're fixing something that can be tested (the pure-logic bits like `HyperionGrabberOptions` and `UpdateChecker` already have tests).
5. Run the build and the unit tests before opening the PR.
6. Open a pull request using the template and describe what changed and why.

## Style notes

- Follow the style of the surrounding code (Java and Kotlin both exist in this repo).
- Don't add comments unless they explain something non-obvious.
- Don't bump the version or touch the changelog unless you're asked to - releases are handled separately.

## Code of conduct

Be kind and constructive in issues, PRs, and comments. Harassment or abusive behavior will get you banned.
