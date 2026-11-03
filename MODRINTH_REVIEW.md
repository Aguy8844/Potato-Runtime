# Modrinth Technical Review Guide - Potato Runtime 0.9.0

Thank you for reviewing Potato Runtime.

This file exists because Potato is a low-level client performance mod and the normal project description intentionally avoids forcing users to read implementation details.

## Quick verification links

- **Public source:** https://github.com/Aguy8844/Potato-Runtime
- **Exact submitted-source commit:** https://github.com/Aguy8844/Potato-Runtime/tree/aeca44e2ac3e0a0a796b13a687bc3953d9fa057a
- **Build instructions:** [BUILDING.md](BUILDING.md)
- **Mixin / hook manifest:** [docs/MIXIN_HOOK_MANIFEST.md](docs/MIXIN_HOOK_MANIFEST.md)
- **Authoritative mixin config:** [src/main/resources/potato_runtime.mixins.json](src/main/resources/potato_runtime.mixins.json)
- **Compatibility gate:** [PotatoMixinCompatibilityPlugin.java](src/main/java/dev/ordovicium/potato/mixin/PotatoMixinCompatibilityPlugin.java)
- **Benchmark protocol:** [docs/BENCHMARK_PROTOCOL.md](docs/BENCHMARK_PROTOCOL.md)
- **Research provenance:** [docs/RESEARCH_BASIS.md](docs/RESEARCH_BASIS.md)

## What Potato Runtime changes

Potato Runtime primarily changes client render/chunk work:

- chunk/mesh compile scheduling;
- render-upload pacing;
- client-side visible-work prioritization;
- renderer lifecycle/target seams used by the ongoing Vulkan architecture;
- a small modifier-key compatibility bridge for renderer/window transitions.

It does **not** alter server game logic, world saving, player movement, authentication, networking, accounts, or credentials.

## Why the JAR contains native files

The Gradle build embeds official **LWJGL 3.3.3 Vulkan + Shaderc** modules, including the official Windows Shaderc native artifact.

This exists for the Vulkan renderer research path. The final tested 0.9.0 Sodium + Iris profile does **not** activate Vulkan gameplay rendering; it safely uses OpenGL compatibility.

There are no custom Potato native binaries. The source has no `System.load` or `System.loadLibrary` call.

## Threading model

Potato uses Java executor/ForkJoin scheduling for asynchronous chunk-mesh compilation. The intent is to bound client background work and leave CPU capacity for the integrated server, world generation, render thread, I/O, and GC.

It does not create a networking worker, remote-control worker, downloader, or background process executor.

## Source/runtime transparency

Repository search of the published 0.9.0 source shows no use of:

- `ProcessBuilder`
- sockets / server sockets
- Java `HttpClient`
- `URLClassLoader`
- `System.load`
- `System.loadLibrary`

The diagnostic subsystem writes local startup/Vulkan reports; it does not upload them.

## Submitted artifact identity

Audited release artifact:

```text
potato_runtime-0.9.0.jar
SHA-256: 78964806DB10C877ABB9EC816C612E8BAD1AE46E2E90E3084E32FE180E97ECBF
Size: 8,742,999 bytes
```

The source-publication audit verified the public source against the final release source hashes before GitHub publication.

## Known metadata discrepancy

The repository is now MPL-2.0 licensed. The exact 0.9.0 submitted build inputs still contain the older `All Rights Reserved` mod-metadata property.

That value was deliberately not silently changed in the pinned submitted-source commit because doing so would make the public source differ from the artifact already under review. A rebuilt follow-up artifact should align the embedded metadata with MPL-2.0.

## AI disclosure

Development used AI-assisted engineering/review workflows. The project should use Modrinth's applicable AI-generated-content disclosure in accordance with the current Modrinth content rules.
