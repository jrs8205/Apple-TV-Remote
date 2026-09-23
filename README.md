# Apple TV Remote

An open Android remote control for Apple TV. It talks to the Apple TV directly over the local
network using the Companion Link protocol, so no account, cloud service or extra hardware is needed.

## Features

- Finds Apple TVs on the local network and pairs with the PIN shown on the TV
- Siri Remote style layout with the controls placed near the bottom of the screen
- Touchpad, directional swipe and d-pad navigation modes
- Play/pause, skip, volume, mute, back, home and power
- Text entry when the Apple TV shows a keyboard
- Playback controls in the notification shade and a Quick Settings tile
- English and Finnish

## Building

Open the project in Android Studio or run:

```
gradlew.bat assembleDebug
```

Unit tests run once with:

```
gradlew.bat :app:testDebugUnitTest --console=plain
```

Requires Android 14 (API 34) or newer. On Android 17 the app asks for local network access,
which it needs to discover and reach the Apple TV.

## License

GPL-3.0. See [LICENSE](LICENSE).
