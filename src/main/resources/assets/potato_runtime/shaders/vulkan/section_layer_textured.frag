#version 450

layout(set = 0, binding = 0) uniform sampler2D Sampler0;

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;

layout(location = 0) out vec4 FragColor;

layout(push_constant) uniform SectionPush {
    mat4 mvp;
    vec4 chunkOffset;
    vec4 colorModulator;
    vec4 fogColor;
    vec4 fogParams;
} pc;

vec4 linear_fog(
    vec4 inColor,
    float distanceToVertex,
    float fogStart,
    float fogEnd,
    vec4 fogColor
) {
    if (distanceToVertex <= fogStart) {
        return inColor;
    }

    float fogValue =
        distanceToVertex < fogEnd
        ? smoothstep(
            fogStart,
            fogEnd,
            distanceToVertex
        )
        : 1.0;

    return vec4(
        mix(
            inColor.rgb,
            fogColor.rgb,
            fogValue * fogColor.a
        ),
        inColor.a
    );
}

void main() {
    vec4 color =
        texture(
            Sampler0,
            texCoord0
        )
        * vertexColor
        * pc.colorModulator;

    float alphaCutoff =
        pc.fogParams.w;

    if (alphaCutoff > 0.0
            && color.a < alphaCutoff) {
        discard;
    }

    FragColor =
        linear_fog(
            color,
            vertexDistance,
            pc.fogParams.x,
            pc.fogParams.y,
            pc.fogColor
        );
}
