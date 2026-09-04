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

struct TileAttribute {
    uint color0;
    uint color1;
    uint color2;
    uint color3;

    uint lightmap0;
    uint lightmap1;
    uint lightmap2;
    uint lightmap3;

    uint minURaw;
    uint maxURaw;
    uint minVRaw;
    uint maxVRaw;

    uint orientation;
    uint normal;
    uint direction;
    uint cornerOrder;
};

layout(std430, set = 0, binding = 0) readonly buffer RectangleBuffer {
    RectangleDescriptor rectangles[];
};

layout(std430, set = 0, binding = 1) readonly buffer TileBuffer {
    TileAttribute tiles[];
};

layout(set = 0, binding = 2) uniform sampler2D Sampler0;
layout(set = 0, binding = 3) uniform sampler2D Sampler2;

layout(location = 0) flat in uint rectangleIndex;
layout(location = 1) in vec2 rectangleCoord;

layout(location = 0) out vec4 FragColor;

vec4 unpack_rgba8(uint packed) {
    return vec4(
        float(packed & 0xFFu),
        float((packed >> 8u) & 0xFFu),
        float((packed >> 16u) & 0xFFu),
        float((packed >> 24u) & 0xFFu)
    ) / 255.0;
}

ivec2 unpack_lightmap(uint packed) {
    return ivec2(
        int(packed & 0xFFFFu),
        int((packed >> 16u) & 0xFFFFu)
    );
}

vec4 minecraft_sample_lightmap(ivec2 uv) {
    return textureLod(
        Sampler2,
        clamp(
            vec2(uv) / 256.0,
            vec2(0.5 / 16.0),
            vec2(15.5 / 16.0)
        ),
        0.0
    );
}

vec2 canonical_position(uint corner) {
    return vec2(
        float(corner & 1u),
        float((corner >> 1u) & 1u)
    );
}

uint original_corner(
    TileAttribute tile,
    uint originalVertex
) {
    return (
        tile.cornerOrder
        >> (originalVertex * 2u)
    ) & 3u;
}

vec2 atlas_corner_uv(
    TileAttribute tile,
    uint canonicalCorner
) {
    uint uvCode =
        (
            tile.orientation
            >> (canonicalCorner * 2u)
        ) & 3u;

    float minU =
        uintBitsToFloat(
            tile.minURaw
        );

    float maxU =
        uintBitsToFloat(
            tile.maxURaw
        );

    float minV =
        uintBitsToFloat(
            tile.minVRaw
        );

    float maxV =
        uintBitsToFloat(
            tile.maxVRaw
        );

    return vec2(
        (uvCode & 1u) == 0u
            ? minU
            : maxU,
        (uvCode & 2u) == 0u
            ? minV
            : maxV
    );
}

vec3 barycentric(
    vec2 p,
    vec2 a,
    vec2 b,
    vec2 c
) {
    float denominator =
        (b.y - c.y) * (a.x - c.x)
        + (c.x - b.x) * (a.y - c.y);

    if (abs(denominator) < 0.000001) {
        return vec3(
            -1.0
        );
    }

    float wa =
        (
            (b.y - c.y) * (p.x - c.x)
            + (c.x - b.x) * (p.y - c.y)
        ) / denominator;

    float wb =
        (
            (c.y - a.y) * (p.x - c.x)
            + (a.x - c.x) * (p.y - c.y)
        ) / denominator;

    return vec3(
        wa,
        wb,
        1.0 - wa - wb
    );
}

bool inside_triangle(vec3 weights) {
    return weights.x >= -0.0001
        && weights.y >= -0.0001
        && weights.z >= -0.0001;
}

void main() {
    RectangleDescriptor rectangle =
        rectangles[rectangleIndex];

    vec2 extent =
        max(
            vec2(
                float(rectangle.width),
                float(rectangle.height)
            ),
            vec2(1.0)
        );

    vec2 bounded =
        clamp(
            rectangleCoord,
            vec2(0.0),
            extent - vec2(0.0001)
        );

    ivec2 tileCell =
        ivec2(
            floor(
                bounded
            )
        );

    uint tileIndex =
        uint(
            rectangle.tileBase
            + tileCell.y * rectangle.width
            + tileCell.x
        );

    TileAttribute tile =
        tiles[tileIndex];

    vec2 tilePosition =
        fract(
            bounded
        );

    vec4 shade[4];

    shade[0] =
        unpack_rgba8(
            tile.color0
        )
        * minecraft_sample_lightmap(
            unpack_lightmap(
                tile.lightmap0
            )
        );

    shade[1] =
        unpack_rgba8(
            tile.color1
        )
        * minecraft_sample_lightmap(
            unpack_lightmap(
                tile.lightmap1
            )
        );

    shade[2] =
        unpack_rgba8(
            tile.color2
        )
        * minecraft_sample_lightmap(
            unpack_lightmap(
                tile.lightmap2
            )
        );

    shade[3] =
        unpack_rgba8(
            tile.color3
        )
        * minecraft_sample_lightmap(
            unpack_lightmap(
                tile.lightmap3
            )
        );

    vec2 atlasUv[4];

    atlasUv[0] =
        atlas_corner_uv(
            tile,
            0u
        );

    atlasUv[1] =
        atlas_corner_uv(
            tile,
            1u
        );

    atlasUv[2] =
        atlas_corner_uv(
            tile,
            2u
        );

    atlasUv[3] =
        atlas_corner_uv(
            tile,
            3u
        );

    uint c0 =
        original_corner(
            tile,
            0u
        );

    uint c1 =
        original_corner(
            tile,
            1u
        );

    uint c2 =
        original_corner(
            tile,
            2u
        );

    uint c3 =
        original_corner(
            tile,
            3u
        );

    vec3 firstWeights =
        barycentric(
            tilePosition,
            canonical_position(
                c0
            ),
            canonical_position(
                c1
            ),
            canonical_position(
                c2
            )
        );

    vec4 interpolatedShade;
    vec2 interpolatedAtlasUv;

    if (inside_triangle(
            firstWeights
    )) {
        interpolatedShade =
            firstWeights.x * shade[c0]
            + firstWeights.y * shade[c1]
            + firstWeights.z * shade[c2];

        interpolatedAtlasUv =
            firstWeights.x * atlasUv[c0]
            + firstWeights.y * atlasUv[c1]
            + firstWeights.z * atlasUv[c2];
    } else {
        vec3 secondWeights =
            barycentric(
                tilePosition,
                canonical_position(
                    c2
                ),
                canonical_position(
                    c3
                ),
                canonical_position(
                    c0
                )
            );

        interpolatedShade =
            secondWeights.x * shade[c2]
            + secondWeights.y * shade[c3]
            + secondWeights.z * shade[c0];

        interpolatedAtlasUv =
            secondWeights.x * atlasUv[c2]
            + secondWeights.y * atlasUv[c3]
            + secondWeights.z * atlasUv[c0];
    }

    vec4 atlasColor =
        textureLod(
            Sampler0,
            interpolatedAtlasUv,
            0.0
        );

    FragColor =
        atlasColor
        * interpolatedShade;
}