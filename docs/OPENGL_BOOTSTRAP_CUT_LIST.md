# OpenGL Bootstrap Cut List

Patch 015c is evidence-gathering only.

The audit source is the exact runtime classpath resolved by ModDevGradle from
sourceSets.main.runtimeClasspath; no NeoForm cache filename is assumed.

Canonical generated audit:

_dropoff/state/015_opengl_bootstrap/cut-list.txt

Raw javap disassemblies and the resolved runtime classpath are retained beside
it.

## Gate

No main-window Vulkan mutation is allowed until Patch 016 converts this runtime
evidence into explicit backend ownership boundaries.

We replace responsibilities, not random individual GL calls.