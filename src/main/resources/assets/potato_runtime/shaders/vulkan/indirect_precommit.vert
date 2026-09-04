#version 450

layout(location = 0) out vec3 vColor;

const vec2 TRIANGLE[3] = vec2[](
    vec2(-0.32, -0.28),
    vec2( 0.32, -0.28),
    vec2( 0.00,  0.34)
);

void main() {
    uint instance = uint(gl_InstanceIndex);
    uint column = instance & 15u;
    uint row = (instance >> 4u) & 15u;

    vec2 cell = vec2(float(column), float(row));
    vec2 center = (cell + vec2(0.5)) / 8.0 - vec2(0.9375);
    vec2 local = TRIANGLE[gl_VertexIndex % 3] * 0.105;

    gl_Position = vec4(center + local, 0.0, 1.0);

    float hue = fract(float(instance) * 0.61803398875);
    vColor = vec3(0.25 + 0.70 * hue, 0.90 - 0.45 * hue, 0.55 + 0.35 * (1.0 - hue));
}
