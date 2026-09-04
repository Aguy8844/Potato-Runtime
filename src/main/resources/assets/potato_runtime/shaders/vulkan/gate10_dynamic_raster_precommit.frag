#version 450

layout(location = 0) flat in uint validPacket;
layout(location = 1) in vec4 debugColor;

layout(location = 0) out vec4 outColor;

void main() {
    if (validPacket == 0u) {
        discard;
    }

    outColor = debugColor;
}
