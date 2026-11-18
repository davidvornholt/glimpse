# Glimpse

> Built on [davidvornholt/standards](https://github.com/davidvornholt/standards).

Glimpse hands a photo from your Android phone to a coding agent without putting it in your photo library. You take the photo in the Glimpse app, it goes to your own Glimpse server, and the agent looks at it with the `glimpse` command-line tool. The server deletes every photo after 30 days.

## Android app

The app (`apps/android`) needs Android 12 or newer. Every push to `main` that changes it publishes a signed APK as the GitHub release "Glimpse for Android 1.N", tagged `android-v1.N`.

Install and update it with [Obtainium](https://github.com/ImranR98/Obtainium). Add an app with the source URL `https://github.com/davidvornholt/glimpse` and set these options before you add it:

- **Filter release titles by regex**: `^Glimpse for Android `, with **Fallback to older releases** on, so Obtainium skips any other release in this repository.
- **Version string extraction regular expression**: `[0-9]+\.[0-9]+`, so Obtainium compares the `1.N` in the tag with the installed version.

The releases are signed with the certificate whose SHA-256 fingerprint is `33:0E:96:A0:E9:8C:95:07:F6:0D:E9:0C:29:D4:68:1D:A4:07:DD:AB:20:04:59:BE:AB:DF:81:2C:12:AC:07:9E`.

To pair the phone, tap **Scan code** in the app and scan the code `glimpse pair` draws. When the server's address is on your local network, Android asks the app for access to it; allow it, because Android otherwise drops the connection without an error.

`nix develop` also provides the Android SDK. In `apps/android`, `./gradlew :app:installDebug` installs a debug build, `io.github.davidvornholt.glimpse.debug`, next to the released app.

## License

[MIT](LICENSE) © David Vornholt
