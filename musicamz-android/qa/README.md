# Release APK ZIP-handler check

Run with Python 3.9 or newer; no packages, Android SDK, emulator or device required:

```sh
python qa/check_zip_handlers.py /path/to/MusicAmz-release.apk app/build/outputs/mapping/release/mapping.txt
```

Use the **final signed release APK and the mapping from that exact build**. Run it
for each distributed APK. Exit codes: `0` passes, `1` detects a regression or
missing mapped handler, `2` indicates unreadable/unsupported input. The checker
does not install the APK, execute its code, modify files, or access user data.

MusicAmz 1.2.1 allowed R8 to turn Commons Compress ZIP extra-field classes into
abstract classes without constructors. `ExtraFieldUtils` instantiates these
classes by reflection during its static initialization. A fresh installation
therefore failed to unpack Python/FFmpeg with `ExceptionInInitializerError`,
which the app incorrectly described as an incompatible audio engine. Tests with
previously extracted runtime files could miss this failure.

The checker reads each `classes*.dex` inside the APK and resolves class names with
the R8 mapping. Every registered handler must remain public, concrete, and have a
public, concrete `<init>()V` method. It checks the 13 classes actually registered
by `ExtraFieldUtils` in **Commons Compress 1.12**, the dependency of
**youtubedl-android 0.18.1**. This list was verified from the cached dependency's
`ExtraFieldUtils` bytecode (`javap -p -c`); it deliberately excludes abstract ZIP
base classes. If that dependency or its registry changes, review and update
`REGISTERED_HANDLERS` in the script.

The corresponding ProGuard rule is:

```proguard
-keep,allowobfuscation class * implements org.apache.commons.compress.archivers.zip.ZipExtraField {
    public <init>();
}
```

This is a focused packaging regression check. It does not verify APK signatures,
establish that a supplied mapping belongs to an APK, or replace a fresh-install
runtime initialization test and an end-to-end download test on Android.

## Reproduce the 1.2.1 failure

The original full mapping was not archived. The fixture below contains just the
`AsiExtraField` mapping actually observed in that build, rather than attempting
to reconstruct the other aliases. This targeted command checks only that class:

```sh
python qa/check_zip_handlers.py /path/to/MusicAmz-v1.2.1-arm64-v8a.apk qa/fixtures/1.2.1-observed-asi-mapping.txt --handler AsiExtraField
```

Expected exit code: `1`, reporting that `w4.a` is abstract and has no public
zero-argument constructor. For current release validation, use the complete
mapping and omit `--handler` so all 13 registered classes are checked.
