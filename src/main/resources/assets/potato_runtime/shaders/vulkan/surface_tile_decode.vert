#version 450

struct RectangleDescriptor {
    int direction;
    int plane;
    int cellA;
    int cellB;
    int width;
    int height;
    int tileBase;
    int tileCount;
};

layout(std430, set = 0, binding = 0) readonly buffer RectangleBuffer {
    RectangleDescriptor rectangles[];
};

layout(location = 0) flat out uint rectangleIndex;
layout(location = 1) out vec2 rectangleCoord;

const vec2 TRIANGLE_CORNERS[6] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(1.0, 1.0),
    vec2(0.0, 0.0),
    vec2(1.0, 1.0),
    vec2(0.0, 1.0)
);

void main() {
    uint rectangle =
        uint(gl_VertexIndex) / 6u;

    uint corner =
        uint(gl_VertexIndex) % 6u;

    RectangleDescriptor descriptor =
        rectangles[rectangle];

    vec2 extent =
        vec2(
            float(descriptor.width),
            float(descriptor.height)
        );

    vec2 local =
        TRIANGLE_CORNERS[corner]
        * extent;

    rectangleIndex =
        rectangle;

    rectangleCoord =
        local;

    vec2 sectionPosition =
        vec2(
            float(descriptor.cellA),
            float(descriptor.cellB)
        )
        + local;

    vec2 normalizedSection =
        clamp(
            sectionPosition / 16.0,
            vec2(0.0),
            vec2(1.0)
        );

    int direction =
        clamp(
            descriptor.direction,
            0,
            5
        );

    int panelX =
        direction % 3;

    int panelY =
        direction / 3;

    vec2 panelScale =
        vec2(
            2.0 / 3.0,
            1.0
        );

    vec2 panelOrigin =
        vec2(
            -1.0 + float(panelX) * panelScale.x,
            -1.0 + float(panelY) * panelScale.y
        );

    vec2 inset =
        vec2(
            0.04,
            0.06
        );

    vec2 panelPosition =
        panelOrigin
        + inset
        + normalizedSection
        * (panelScale - 2.0 * inset);

    gl_Position =
        vec4(
            panelPosition,
            0.0,
            1.0
        );
}