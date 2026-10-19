#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

uniform highp samplerExternalOES uCameraTexture;
uniform highp sampler3D uLutTexture;
uniform mat4 uTextureTransform;
uniform vec4 uCropRect;
uniform int uRotationDegrees;
uniform vec3 uDomainMin;
uniform vec3 uDomainMax;
uniform float uLutIntensity;
uniform bool uHasLut;

in vec2 vTextureCoord;
out vec4 fragColor;

void main() {
    vec2 cameraUv = (uTextureTransform * vec4(vTextureCoord, 0.0, 1.0)).xy;
    cameraUv = uCropRect.xy + cameraUv * uCropRect.zw;
    if (uRotationDegrees == 90) cameraUv = vec2(cameraUv.y, 1.0 - cameraUv.x);
    else if (uRotationDegrees == 180) cameraUv = vec2(1.0 - cameraUv.x, 1.0 - cameraUv.y);
    else if (uRotationDegrees == 270) cameraUv = vec2(1.0 - cameraUv.y, cameraUv.x);
    vec4 cameraColor = texture(uCameraTexture, cameraUv);
    vec3 outputRgb = cameraColor.rgb;
    if (uHasLut) {
        vec3 lutUv = clamp((cameraColor.rgb - uDomainMin) / (uDomainMax - uDomainMin), 0.0, 1.0);
        outputRgb = mix(cameraColor.rgb, texture(uLutTexture, lutUv).rgb, clamp(uLutIntensity, 0.0, 1.0));
    }
    fragColor = vec4(outputRgb, cameraColor.a);
}
