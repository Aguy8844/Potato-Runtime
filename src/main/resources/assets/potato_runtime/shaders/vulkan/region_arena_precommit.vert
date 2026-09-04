#version 450

layout(location = 0) in vec3 inPosition;

void main() {
    vec3 local = mod(abs(inPosition), vec3(17.0));
    vec2 projected = vec2(
        local.x + local.z * 0.37,
        local.y + local.z * 0.21
    );

    projected = projected / vec2(12.0, 11.0) - vec2(0.85);
    gl_Position = vec4(projected, 0.0, 1.0);
}
