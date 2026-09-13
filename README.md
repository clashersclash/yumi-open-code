# Rumi Controller

This personal Android companion app receives this broadcast from Termux:

```text
Action: com.rumi.voiceassistant.PLAY_SPOTIFY
Extra:  query=<song request>
```

It uses an Accessibility Service, enabled manually by the phone owner, to click a matching Spotify result and then a visible Play control.

## Build and install

1. Open this `RumiController` folder in Android Studio.
2. Let Android Studio download the requested SDK/Gradle components.
3. Select **Build > Build APK(s)**.
4. Install `app-debug.apk` on the phone.
5. Open **Rumi Controller** and choose **Open Accessibility Settings**.
6. Enable **Rumi Spotify Controller**.

The Termux Rumi script in the parent `outputs` folder already sends the broadcast after it launches Spotify.

## Important limitations

- The phone must be unlocked and Spotify must be in the foreground.
- Spotify can change its accessibility labels or layout. If a result is not selected, adjust the Kotlin service's matching strategy or use a fixed UI Interaction tool instead.
- This project is intended for personal sideloading. Do not publish an Accessibility-based controller without reviewing Google Play's Accessibility API policy.
