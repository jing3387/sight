#version 150 core

uniform vec2 u_lightPosition;
in vec2 position;
out vec2 lightPosition;

void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    lightPosition = u_lightPosition;
}