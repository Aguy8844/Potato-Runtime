#version 450

layout(std430, set = 0, binding = 0) readonly buffer Gate10StatePackets {
    uint words[];
} statePackets;

layout(push_constant) uniform Gate10Push {
    uint domain;
} pc;

layout(location = 0) flat out uint validPacket;
layout(location = 1) out vec4 debugColor;

const uint MAGIC = 0x47543130u;

uint expectedFlags(uint domain) {
    if (domain == 0u) {
        return 51u;
    }

    if (domain == 1u) {
        return 53u;
    }

    return 44u;
}

uint expectedSrcBlend(uint domain) {
    return domain == 0u
            ? 1u
            : 6u;
}

uint expectedDstBlend(uint domain) {
    return domain == 0u
            ? 0u
            : 7u;
}

void main() {
    uint base = pc.domain * 8u;

    uint magic = statePackets.words[base + 0u];
    uint recordedDomain = statePackets.words[base + 1u];
    uint width = statePackets.words[base + 2u];
    uint height = statePackets.words[base + 3u];
    uint flags = statePackets.words[base + 4u];
    uint srcBlend = statePackets.words[base + 5u];
    uint dstBlend = statePackets.words[base + 6u];

    bool valid =
            pc.domain < 4u
            && magic == MAGIC
            && recordedDomain == pc.domain
            && width > 0u
            && height > 0u
            && flags == expectedFlags(pc.domain)
            && srcBlend == expectedSrcBlend(pc.domain)
            && dstBlend == expectedDstBlend(pc.domain);

    validPacket = valid
            ? 1u
            : 0u;

    vec2 position;

    if (gl_VertexIndex == 0) {
        position = vec2(-0.65, -0.55);
    } else if (gl_VertexIndex == 1) {
        position = vec2(0.65, -0.55);
    } else {
        position = vec2(0.0, 0.65);
    }

    float aspect =
            clamp(
                    float(width) / float(max(height, 1u)),
                    0.5,
                    2.0
            );

    position.x /= aspect;

    if (!valid) {
        position = vec2(2.0, 2.0);
    }

    gl_Position =
            vec4(
                    position,
                    pc.domain < 2u
                            ? 0.25
                            : 0.0,
                    1.0
            );

    if (pc.domain == 0u) {
        debugColor = vec4(1.0, 0.2, 0.2, 1.0);
    } else if (pc.domain == 1u) {
        debugColor = vec4(0.2, 1.0, 0.2, 0.5);
    } else if (pc.domain == 2u) {
        debugColor = vec4(0.2, 0.4, 1.0, 0.5);
    } else {
        debugColor = vec4(1.0, 0.5, 0.1, 0.5);
    }
}
