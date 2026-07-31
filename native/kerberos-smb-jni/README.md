# Native Kerberos/SMB bridge

This crate supplies true Kerberos authentication for Android without JAAS or
JGSS. It performs the Kerberos AS/TGS/AP exchange and then accesses SMB2/3
through the pinned `smb2` Rust crate.

Build the Android libraries from PowerShell:

```powershell
.\build-android.ps1
```

The script builds API 24 libraries for `x86_64`, `arm64-v8a` and
`armeabi-v7a`, then copies them to `app/src/main/jniLibs`. Normal Gradle builds
package these prebuilt libraries and do not require Rust or the NDK.

Do not install a Rust logging backend for this bridge. The current upstream
Kerberos implementation emits key prefixes at debug level.
