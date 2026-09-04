#version 450

layout(location = 0) in vec3 Position;

layout(push_constant) uniform SectionPush {
    mat4 mvp;
    vec4 chunkOffset;
} pc;

void main() {
    vec4 clip =
        pc.mvp
        * vec4(
            Position + pc.chunkOffset.xyz,
            1.0
        );

    // Minecraft's matrices target OpenGL clip conventions.
    clip.y = -clip.y;
    clip.z = 0.5 * (clip.z + clip.w);

    gl_Position = clip;
}
