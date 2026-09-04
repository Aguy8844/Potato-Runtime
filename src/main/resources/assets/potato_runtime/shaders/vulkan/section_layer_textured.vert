#version 450

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(set = 0, binding = 1) uniform sampler2D Sampler2;

layout(location = 0) out float vertexDistance;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec2 texCoord0;

layout(push_constant) uniform SectionPush {
    mat4 mvp;
    vec4 chunkOffset;
    vec4 colorModulator;
    vec4 fogColor;
    vec4 fogParams;
} pc;

float fog_distance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    }

    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}

vec4 minecraft_sample_lightmap(
    sampler2D lightMap,
    ivec2 uv
) {
    return textureLod(
        lightMap,
        clamp(
            vec2(uv) / 256.0,
            vec2(0.5 / 16.0),
            vec2(15.5 / 16.0)
        ),
        0.0
    );
}

void main() {
    vec3 pos =
        Position
        + pc.chunkOffset.xyz;

    vec4 clip =
        pc.mvp
        * vec4(
            pos,
            1.0
        );

    /*
     * Minecraft matrices target OpenGL clip conventions.
     */
    clip.y = -clip.y;
    clip.z = 0.5 * (clip.z + clip.w);

    gl_Position =
        clip;

    vertexDistance =
        fog_distance(
            pos,
            int(pc.fogParams.z)
        );

    vertexColor =
        Color
        * minecraft_sample_lightmap(
            Sampler2,
            UV2
        );

    texCoord0 =
        UV0;
}
