#version 450

layout(location = 0) out vec3 vertexColor;

layout(push_constant) uniform FramePush {
    float angleRadians;
} framePush;

const vec2 POSITIONS[3] = vec2[](
    vec2( 0.00, -0.72),
    vec2( 0.72,  0.58),
    vec2(-0.72,  0.58)
);

const vec3 COLORS[3] = vec3[](
    vec3(0.98, 0.72, 0.18),
    vec3(0.18, 0.82, 0.38),
    vec3(0.24, 0.52, 0.96)
);

void main() {
    float c = cos(framePush.angleRadians);
    float s = sin(framePush.angleRadians);

    mat2 rotation = mat2(
         c, s,
        -s, c
    );

    vec2 position = rotation * POSITIONS[gl_VertexIndex];

    gl_Position = vec4(position, 0.0, 1.0);
    vertexColor = COLORS[gl_VertexIndex];
}