# Building Potato Runtime 0.9.0

This page is intentionally written for reviewers and anyone who wants to verify the submitted artifact from public source.

## Exact source for the submitted 0.9.0 artifact

- Repository: https://github.com/Aguy8844/Potato-Runtime
- Audited source commit: `aeca44e2ac3e0a0a796b13a687bc3953d9fa057a`
- Minecraft: `1.21.1`
- NeoForge: `21.1.249`
- Java: `21`
- Gradle wrapper: `9.2.1`
- Mod version: `0.9.0`

The source publication step verified the uploaded tree against the final 171c release-source hashes before it was pushed to GitHub.

## Build on Windows

```powershell
git clone https://github.com/Aguy8844/Potato-Runtime.git
cd Potato-Runtime
git checkout aeca44e2ac3e0a0a796b13a687bc3953d9fa057a
.\gradlew.bat clean build --no-daemon
```

Expected output:

```text
build/libs/potato_runtime-0.9.0.jar
```

The audited release artifact had:

```text
SHA-256: 78964806DB10C877ABB9EC816C612E8BAD1AE46E2E90E3084E32FE180E97ECBF
Size:    8,742,999 bytes
```

The JAR task disables file timestamps and enables reproducible file ordering. Exact byte-for-byte reproduction additionally depends on using the same toolchain and dependency artifacts as the audited release build.

## Relevant dependencies

Sodium integration is compile-only:

```text
net.caffeinemc:sodium-neoforge-api:0.8.12+mc1.21.1
```

Potato Runtime remains loadable without Sodium.

The release embeds the following official LWJGL 3.3.3 modules:

```text
org.lwjgl:lwjgl-vulkan:3.3.3
org.lwjgl:lwjgl-shaderc:3.3.3
org.lwjgl:lwjgl-shaderc:3.3.3:natives-windows
```

They are resolved by Gradle from Maven dependencies. Potato Runtime does not include a separately developed/custom native DLL and does not call `System.load` or `System.loadLibrary` itself.

Minecraft 1.21.1 already uses LWJGL 3.3.3, so the Vulkan and Shaderc modules deliberately stay on that same line. `lwjgl-core` is not shaded.

## Automated verification build

The repository contains a GitHub Actions workflow at:

```text
.github/workflows/build.yml
```

It runs the Gradle wrapper with Java 21 on Windows and uploads the resulting JAR as a workflow artifact.

## License metadata note

The repository is licensed under MPL-2.0 through the root `LICENSE` file.

The audited 0.9.0 source commit still contains the historical build property `mod_license=All Rights Reserved`, because changing it after the artifact had already been built/submitted would make that commit no longer represent the exact submitted build inputs. This mismatch should be corrected in the next rebuilt release artifact.
