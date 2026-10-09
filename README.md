# MediaForge Android

A native Android companion that opens your MediaForge server in a WebView.
Scan a pairing QR code or enter your server URL, username and password once.
Device credentials and passwords are encrypted with Android Keystore, and the app
signs in automatically when opened.

## Download

Download the signed APK from the [latest release](https://github.com/ptaiton/mediaforge-android/releases/latest)
and install it on Android 8.0 or newer. Enter your own MediaForge server URL and
credentials on first launch. Use HTTPS when connecting over the internet.

## Connect with a QR code

Open **Quick connection** in the server's left sidebar while signed in to the
account you want to use. Its username is filled automatically. Set a server
address reachable from your phone, then tap **Scan QR code** in the Android app
and confirm the displayed server and account. The app signs in directly without
asking for your password.

Pairing codes expire after two minutes and work only once. Keep the QR code
private because it grants access to your account. The app stores its separate
device credential encrypted with Android Keystore; the server stores only hashes.
You can disconnect a phone from the same web page, immediately invalidating its
sessions and removing its push registration. Device access expires after 90 days
without signing in. Scan a new code to reconnect.

Manual username/password setup and older QR codes that only fill the login form
remain supported. Changing servers or accounts clears the previous push settings
and web session.

## In-app updates

The app checks the public GitHub releases when opened or resumed, at most once
every six hours. You can also choose **Check for updates** in the companion
settings. Downloading requires confirmation; the app verifies the checksum,
package name, newer version and signing certificate before opening the Android
installer. Android may first ask you to allow installations from MediaForge.
Server settings and credentials are preserved by an update.

The first version with this updater must be installed manually. Subsequent
signed releases can be installed from inside the app. Debug builds use a
different signing certificate and cannot update to a release APK in place.

## Mobile notifications

Configure Firebase in your server's **Settings → Notifications → Mobile** tab:

1. Create a Firebase project and register an Android app with the package name
   `com.mediaforge.android`.
2. Download `google-services.json` for that Android app and generate a private
   service account JSON key in **Project settings → Service accounts**.
3. Enable the Firebase Cloud Messaging API if necessary. Import both files in the
   server settings, enable mobile notifications and save.
4. Sign in from the Android app and allow notifications when prompted.

The app retrieves only public Firebase settings after authentication, caches them
locally and restores them when Android starts the process for a background
notification. The private service account stays encrypted on the server. No
Firebase file or key is required when building the APK, and no shared MediaForge
notification relay is needed.

Changing the server or account clears the previous local push configuration.
Reconnect after changing the server's Firebase project. If the server is
unavailable, a working cached configuration is retained. The server's test button
sends a notification to devices registered with the account running the test.

Tap a media notification to open that title in the library, including when the
app is closed. Notifications are bound to the server and account configured when
they arrived; switching either prevents opening a title on the wrong account.

Native screens and notification text support English and French using the phone's
language. The embedded web interface follows the browser language.

## Build

Use JDK 17, Gradle 8.9 and Android SDK 35:

```sh
gradle --no-daemon assembleDebug lintDebug testDebugUnitTest
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Release builds
can use `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` Gradle properties. Keep signing files out of Git.

The GitHub workflow builds and signs an APK when changes reach `main`, then creates
a versioned release. It needs the `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets.
Firebase configuration is fetched from the selected server at runtime.

## Repository privacy

Keep Firebase configuration files, service account keys, signing keystores and
local credentials out of Git. Firebase configuration comes from the server at
runtime; no project configuration belongs in this repository. The signing key
and its passwords are stored as GitHub Actions secrets and are never included
in release assets. Release APKs contain only the public signing certificate.
