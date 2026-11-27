package br.com.coffeelabs.ninho

/** Shaders GLSL ES 3.00 (validados em protótipo antes de irem para o app). */
object Shaders {
    const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNrm;
layout(location=2) in vec3 aCol;
layout(location=3) in vec4 aExt;
layout(location=4) in float aOl;
uniform mat4 uVP;
uniform mat4 uLightVP;
uniform float uMirror;
uniform float uWaterY;
uniform float uTime;
out vec3 vNrm;
out vec3 vCol;
out vec4 vExt;
out vec3 vPos;
out vec4 vLPos;
out float vAbove;
void main() {
    vec3 p = aPos;
    vec3 n = aNrm;
    vAbove = p.y - uWaterY;
    vLPos = uLightVP * vec4(p, 1.0);
    if (uMirror > 0.5) {
        p.y = 2.0 * uWaterY - p.y;
        n.y = -n.y;
        p.x += sin(p.y * 22.0 + uTime * 1.6) * 0.012 * clamp(vAbove, 0.0, 1.5);
    }
    vNrm = n; vCol = aCol; vExt = aExt; vPos = p;
    gl_Position = uVP * vec4(p, 1.0);
}
"""

    const val FS = """#version 300 es
precision highp float;
precision highp sampler2DShadow;
in vec3 vNrm;
in vec3 vCol;
in vec4 vExt;
in vec3 vPos;
in vec4 vLPos;
in float vAbove;
uniform vec3 uLight;
uniform vec3 uLightCol;
uniform vec3 uSkyCol;
uniform vec3 uGroundCol;
uniform vec3 uEye;
uniform vec2 uLook;
uniform vec3 uBelly;
uniform vec3 uLid;
uniform float uScaleK;
uniform float uTime;
uniform float uMirror;
uniform float uShadowOnly;
uniform float uBankPx;
uniform sampler2DShadow uShadow;
out vec4 oColor;

float hash3(vec3 p) { return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453); }
float noise3(vec3 p) {
    vec3 i = floor(p); vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash3(i), hash3(i + vec3(1,0,0)), f.x), mix(hash3(i + vec3(0,1,0)), hash3(i + vec3(1,1,0)), f.x), f.y);
    float b = mix(mix(hash3(i + vec3(0,0,1)), hash3(i + vec3(1,0,1)), f.x), mix(hash3(i + vec3(0,1,1)), hash3(i + vec3(1,1,1)), f.x), f.y);
    return mix(a, b, f.z);
}

float shadowAt(float bias) {
    vec3 lp = vLPos.xyz / vLPos.w * 0.5 + 0.5;
    if (lp.x < 0.0 || lp.x > 1.0 || lp.y < 0.0 || lp.y > 1.0 || lp.z > 1.0) return 1.0;
    float s = 0.0;
    float px = 1.0 / 1024.0;
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++)
        s += texture(uShadow, vec3(lp.xy + vec2(float(i), float(j)) * px * 1.5, lp.z - bias));
    return s / 9.0;
}

void main() {
    if (uShadowOnly > 0.5) {
        // plano da água: só recebe a sombra
        float sh = shadowAt(0.002);
        float fade = smoothstep(4.5, 2.0, length(vPos.xz - vec2(0.0, 0.3)));
        float bank = smoothstep(uBankPx, uBankPx + 30.0, gl_FragCoord.y);
        oColor = vec4(0.05, 0.08, 0.16, (1.0 - sh) * 0.30 * fade * bank);
        return;
    }
    vec3 n = normalize(vNrm);
    if (!gl_FrontFacing) n = -n;
    if (uMirror > 0.5) { n = -n; if (vExt.w > 6.5 && vExt.w < 7.5) discard; }
    vec3 v = normalize(uEye - vPos);
    float mat = vExt.w;
    vec3 col = vCol;
    float spec = 0.25; float shin = 30.0; float wrap = 0.2; float trans = 0.0;
    bool unlit = false;
    if (mat > 0.5 && mat < 1.5) {
        float u = vExt.x, vv = vExt.y;
        float cv = cos(vv * 6.2831853);
        float belly = smoothstep(-0.42, -0.62, cv);
        float a = u * uScaleK;
        float row = floor(a);
        float b = vv * 22.0 + mod(row, 2.0) * 0.5;
        vec2 f = vec2(fract(b) - 0.5, fract(a));
        float d = length(vec2(f.x, f.y * 0.9 + 0.05));
        float edge = smoothstep(0.10, 0.0, abs(d - 0.5)) * step(f.y, 0.62);
        float bump = smoothstep(0.5, 0.0, d);
        vec3 scales = col * (0.86 + 0.20 * bump) * (1.0 - 0.22 * edge);
        float plate = smoothstep(0.10, 0.0, abs(fract(u * uScaleK * 0.55) - 0.5) - 0.40);
        vec3 bellyC = uBelly * (1.0 - 0.16 * plate);
        col = mix(scales, bellyC, belly);
        spec = mix(0.55 * (0.4 + 0.9 * bump), 0.2, belly); shin = mix(48.0, 18.0, belly);
        // escurece o lado de baixo (oclusão)
        col *= mix(1.0, 0.86, smoothstep(0.2, -0.9, cv) * (1.0 - belly));
    } else if (mat > 2.5 && mat < 3.5) {
        vec2 q = vExt.xy;
        float front = vExt.z;
        float open = vCol.x;
        float mode = vCol.y;
        vec3 sclera = vec3(0.95, 0.96, 0.97);
        vec2 ic = uLook * 0.22;
        float di = length(q - ic);
        vec3 iris = mix(vec3(0.25, 0.47, 0.80), vec3(0.62, 0.82, 0.97), smoothstep(-0.6, 0.5, q.y - ic.y));
        float rays = 0.5 + 0.5 * sin(atan(q.y - ic.y, q.x - ic.x) * 26.0);
        iris *= 0.92 + 0.12 * rays * smoothstep(0.3, 0.7, di);
        vec3 c = sclera;
        c = mix(c, iris, smoothstep(0.8, 0.76, di));
        c = mix(c, vec3(0.14, 0.24, 0.44), smoothstep(0.8, 0.76, di) * smoothstep(0.62, 0.78, di));
        c = mix(c, vec3(0.06, 0.10, 0.20), smoothstep(0.4, 0.36, length((q - ic) * vec2(1.0, 0.85))));
        c = mix(c, vec3(1.0), smoothstep(0.2, 0.16, length(q - ic - vec2(-0.24, 0.3))));
        c = mix(c, vec3(1.0), smoothstep(0.09, 0.06, length(q - ic - vec2(0.26, -0.24))) * 0.8);
        if (front < 0.0) c = sclera;
        float lidY = -1.15 + 2.3 * open;
        float lid = smoothstep(lidY - 0.03, lidY + 0.03, q.y);
        float closedK = step(0.5, mode);
        c = mix(c, uLid, max(lid, closedK));
        vec3 ink = vec3(0.20, 0.23, 0.32);
        float lash = smoothstep(0.08, 0.0, abs(q.y - lidY)) * (1.0 - closedK) * step(0.0, front) * step(0.02, open) * step(open, 0.97);
        c = mix(c, ink, lash);
        if (mode > 0.5 && mode < 1.5) {
            float y = -0.12 + 0.55 * (1.0 - 1.5 * q.x * q.x);
            c = mix(c, ink, smoothstep(0.09, 0.03, abs(q.y - y)) * step(abs(q.x), 0.72) * step(0.0, front));
        } else if (mode > 1.5) {
            float y = 0.05 - 0.4 * (1.0 - 1.6 * q.x * q.x);
            c = mix(c, ink, smoothstep(0.09, 0.03, abs(q.y - y)) * step(abs(q.x), 0.7) * step(0.0, front));
        }
        col = c; spec = 0.0; wrap = 0.7;
    } else if (mat > 3.5 && mat < 4.5) {
        float fr = 1.0 - max(dot(n, v), 0.0);
        vec3 iri = 0.5 + 0.5 * cos(6.2831 * (fr * 1.2 + vec3(0.0, 0.33, 0.67)) + uTime * 0.6);
        col = mix(vec3(0.97, 0.96, 0.94), iri * 0.4 + 0.6, 0.45 * fr + 0.15);
        spec = 0.9; shin = 60.0; wrap = 0.6;
    } else if (mat > 4.5 && mat < 5.5) {
        vec3 lp = vExt.xyz;
        col = vec3(0.86, 0.88, 0.92);
        float sp = noise3(lp * 6.5 + 3.1);
        col = mix(col, vec3(0.62, 0.75, 0.92), smoothstep(0.70, 0.78, sp));
        col *= 0.96 + 0.08 * noise3(lp * 30.0);
        float ang = atan(lp.z, lp.x);
        float zz = 0.18 + 0.08 * abs(fract(ang * 3.0 / 3.14159) * 2.0 - 1.0);
        float crackMask = step(abs(ang), vCol.x * 3.3);
        col = mix(col, vec3(0.25, 0.28, 0.36), smoothstep(0.045, 0.0, abs(lp.y - zz)) * crackMask * step(0.01, vCol.x));
        col += vec3(0.5, 0.7, 1.0) * pow(1.0 - max(dot(n, v), 0.0), 2.0) * vCol.y * 0.5;
        spec = 0.5; shin = 40.0;
    } else if (mat > 5.5 && mat < 6.5) {
        // pétala: rosa nas pontas e bordas, creme na base, nervuras finas
        float u = vExt.x, vv = vExt.y;
        float tip = smoothstep(0.15, 1.0, u);
        vec3 base = vec3(0.995, 0.965, 0.93);
        vec3 pinkC = vec3(0.95, 0.56, 0.68);
        col = mix(base, pinkC, clamp(tip * 0.8 + 0.25 * vv * vv, 0.0, 1.0));
        float vein = smoothstep(0.35, 0.5, abs(fract(vv * 7.0 + 0.5) - 0.5));
        col *= 1.0 - 0.07 * vein * tip;
        spec = 0.12; shin = 12.0; wrap = 0.55; trans = 0.35;
    } else if (mat > 6.5 && mat < 7.5) {
        // folha: nervuras radiais, centro claro, borda escura, cera
        float r = vExt.z;
        float a = atan(vExt.y, vExt.x);
        float vein = smoothstep(0.10, 0.0, abs(fract(a * 20.0 / 6.2831) - 0.5) - 0.44) * smoothstep(0.05, 0.25, r);
        vec3 deep = vec3(0.20, 0.40, 0.29), lite = vec3(0.40, 0.58, 0.37);
        col = mix(lite, deep, smoothstep(0.1, 1.0, r));
        col = mix(col, vec3(0.52, 0.67, 0.45), vein * 0.5);
        col *= 0.93 + 0.14 * noise3(vec3(vExt.xy * 9.0, 1.0));
        col = mix(col, vec3(0.72, 0.80, 0.55), smoothstep(0.12, 0.0, r) * 0.6);
        spec = 0.45; shin = 50.0; wrap = 0.3;
    } else if (mat > 7.5 && mat < 8.5) {
        // miolo / estames (amarelo)
        spec = 0.15; wrap = 0.5;
    }
    // iluminação
    float ndl = dot(n, uLight);
    float bias = mix(0.006, 0.0025, clamp(ndl, 0.0, 1.0));
    float sh = shadowAt(bias);
    float diff = clamp((ndl + wrap) / (1.0 + wrap), 0.0, 1.0);
    float back = clamp((-ndl + 0.3) / 1.3, 0.0, 1.0) * trans;
    vec3 amb = mix(uGroundCol, uSkyCol, n.y * 0.5 + 0.5);
    vec3 c = col * (amb + uLightCol * (diff * sh + back));
    vec3 h = normalize(uLight + v);
    c += uLightCol * pow(max(dot(n, h), 0.0), shin) * spec * sh;
    float fres = pow(1.0 - max(dot(n, v), 0.0), 4.0);
    c += uSkyCol * fres * 0.35;
    if (uMirror > 0.5) {
        float a = 0.32 * smoothstep(2.6, 0.0, vAbove) * smoothstep(uBankPx, uBankPx + 30.0, gl_FragCoord.y);
        oColor = vec4(mix(c, uSkyCol * 0.9, 0.25), a);
        return;
    }
    oColor = vec4(c, 1.0);
}
"""

    const val DEPTH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uLightVP;
void main() { gl_Position = uLightVP * vec4(aPos, 1.0); }
"""

    const val DEPTH_FS = """#version 300 es
precision mediump float;
out vec4 oColor;
void main() { oColor = vec4(1.0); }
"""

    const val BG_VS = """#version 300 es
layout(location=0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5); gl_Position = vec4(aPos, 0.999, 1.0); }
"""

    const val BG_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 oColor;
void main() { oColor = vec4(texture(uTex, vUv).rgb, 1.0); }
"""
}
