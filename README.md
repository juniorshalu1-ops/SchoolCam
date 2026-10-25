# SchoolCam

GPS-stamped camera for Android 10+ built with Kotlin and CameraX.

## Features
- Live camera preview, front/back switching and flash toggle
- Capture, review, add note, recapture, save
- Latitude/longitude, local date/time and optional note burned into the JPEG
- Saves to `Pictures/SchoolCam` using MediaStore
- No account or internet connection required

## Build
1. Open this project in Android Studio (recent version).
2. Allow Gradle sync to finish.
3. Connect an Android 10+ device or start an emulator with a camera.
4. Press **Run**. To create an APK: **Build > Build APK(s)**.

## Notes
- Location is best-effort. If a fresh location is unavailable, the watermark says so rather than inventing coordinates.
- Accuracy depends on the phone, permissions, and satellite/network conditions.
- The app does not upload images.
