#version 150 core

in vec2 lightPosition;
out vec4 color;

void main() {
    float constant = 1.0, linear = 1.0, quadratic = 1.0;
    vec2 position = vec2(gl_FragCoord.x, gl_FragCoord.y)/vec2(800,600) * 2.0 - 1.0;
    float distance = length(position - lightPosition);
    float attenuation = constant + linear*distance + quadratic*distance*distance;
    color = vec4(1.0, 1.0, 1.0, 1.0) / attenuation;
}