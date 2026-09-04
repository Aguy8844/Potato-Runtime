# Potato Runtime - Release Benchmark Protocol

The release benchmark must compare separate fresh processes. Do not switch
DYNAMIC / ON / OFF inside one already-running process because the mode boundary
is intentionally startup-latched.

## Primary A/B/C run

Hardware and Java settings must remain unchanged.

1. Render Distance: 16 chunks.
2. Use the same world, start location, camera direction and route.
3. Run **DYNAMIC** in a fresh process.
4. Run **ON** in a fresh process.
5. Run **OFF** in a fresh process.
6. For each run:
   - move/fly through the same terrain for 60-90 seconds;
   - stop for 15-20 seconds;
   - rotate 180 degrees;
   - return once over the warm route;
   - open inventory/GUI;
   - Save & Quit;
   - wait about 10 seconds;
   - Quit Game normally.

## Cold-world test is separate

Do not mix completely fresh world generation into the main frame-pacing A/B
comparison. World generation can dominate CPU time and measure the integrated
server/worldgen stack rather than Potato's client presentation work.

Record cold-world observations separately:
- time until surrounding terrain first becomes available;
- time until chunk fill visibly catches up;
- persistent holes vs temporary unavailable terrain;
- player speed / Elytra use;
- mod count and world-generation mods.

## Metrics to preserve when available

Use only values actually recorded by telemetry; never infer missing numbers.

- mode: OFF / ON / DYNAMIC
- hardware / visible renderer / hardware class
- JVM maximum memory
- mod count / profile
- average FPS where measured
- median FPS / frametime where measured
- P95 / P99 frametime where measured
- stutter/freezes
- chunk compile queue / throughput where measured
- upload count/time-budget pressure where measured
- first-terrain / cold-world latency where measured
- warm 180-degree return behavior
- memory/OOM evidence
- GL/Vulkan/native failure counters
- shutdown cleanliness

## Publication rule

Separate:
1. direct telemetry;
2. subjective tester observations;
3. derived statistics;
4. research motivation.

Do not relabel a subjective observation as a measured value, and do not use a
paper citation as evidence for Potato's own measured performance.