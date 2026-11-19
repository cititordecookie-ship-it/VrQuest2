package com.vrquest

import android.opengl.GLES20

object Gl {
    fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw RuntimeException("Shader: $log") }
        return s
    }
    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }
}

object Shaders {
    /** Textured panel / passthrough (external OES). Rounded corners + hover rim. Premultiplied output. */
    const val PANEL_VS = """
uniform mat4 uMVP; uniform mat4 uTexMat;
attribute vec3 aPos; attribute vec2 aUV;
varying vec2 vUV; varying vec2 vRaw;
void main(){ gl_Position = uMVP*vec4(aPos,1.0); vRaw = aUV; vUV = (uTexMat*vec4(aUV,0.0,1.0)).xy; }
"""
    const val PANEL_FS = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex; uniform float uAlpha; uniform vec2 uSize; uniform float uRadius; uniform float uHover; uniform float uBright;
varying vec2 vUV; varying vec2 vRaw;
void main(){
  vec2 p = (vRaw-0.5)*uSize; vec2 q = abs(p)-uSize*0.5+vec2(uRadius);
  float d = length(max(q,0.0))+min(max(q.x,q.y),0.0)-uRadius;
  float a = 1.0-smoothstep(-0.004,0.004,d);
  vec4 c = texture2D(uTex,vUV);
  float rim = uHover*smoothstep(0.014,0.0,abs(d+0.007));
  vec3 rgb = c.rgb*uBright + vec3(0.30,0.75,1.0)*rim;
  gl_FragColor = vec4(rgb,1.0)*a*uAlpha;
}
"""
    /** Soft shapes (hand outline, cursor). uMode 0 = disc/ribbon, 1 = ring. */
    const val SHAPE_VS = """
uniform mat4 uMVP; attribute vec3 aPos; attribute vec2 aUV; varying vec2 vUV;
void main(){ gl_Position = uMVP*vec4(aPos,1.0); vUV = aUV; }
"""
    const val SHAPE_FS = """
precision mediump float;
uniform vec4 uColor; uniform float uMode; varying vec2 vUV;
void main(){
  float l = length(vUV); float a;
  if (uMode < 0.5) a = 1.0-smoothstep(0.55,1.0,l);
  else a = smoothstep(0.62,0.74,l)*(1.0-smoothstep(0.9,1.0,l));
  gl_FragColor = vec4(uColor.rgb*uColor.a*a, uColor.a*a);
}
"""
    const val FULL_VS = """
attribute vec2 aPos; varying vec2 vNdc;
void main(){ vNdc = aPos; gl_Position = vec4(aPos,0.0,1.0); }
"""
    /** Lens distortion pass (one eye per call). */
    const val DIST_FS = """
precision highp float;
uniform sampler2D uScene; uniform float uEye; uniform vec2 uK; uniform float uAspect; uniform float uCenter; uniform float uZoom;
varying vec2 vNdc;
void main(){
  vec2 p0 = vNdc; p0.x *= uAspect;
  vec2 c = vec2(uCenter*uAspect, 0.0);
  vec2 pc = p0 - c; float r2 = dot(pc,pc);
  float f = 1.0 + uK.x*r2 + uK.y*r2*r2;
  vec2 q = pc*f/uZoom + c; q.x /= uAspect;
  vec2 st = q*0.5+0.5;
  if (st.x<0.0||st.x>1.0||st.y<0.0||st.y>1.0) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }
  st.x = st.x*0.5 + uEye*0.5;
  gl_FragColor = vec4(texture2D(uScene, st).rgb, 1.0);
}
"""
    /** Procedural environments rendered at infinity. */
    const val ENV_FS = """
precision highp float;
uniform mat3 uRot; uniform vec2 uTan; uniform float uTime; uniform int uEnv; uniform float uFlash; uniform sampler2D uPano;
varying vec2 vNdc;
float hash(vec2 p){ p = fract(p*vec2(123.34,456.21)); p += dot(p,p+45.32); return fract(p.x*p.y); }
float hash3(vec3 p){ p = fract(p*0.3183099+0.1); p *= 17.0; return fract(p.x*p.y*p.z*(p.x+p.y+p.z)); }
float noise(vec2 p){ vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
  return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x), mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x), f.y); }
float fbm(vec2 p){ float v=0.0, a=0.5; for(int i=0;i<5;i++){ v+=a*noise(p); p*=2.03; a*=0.5; } return v; }

float rainLayer(vec3 d, float n, float speed, float seed){
  float a = atan(d.x,d.z); float h = d.y/max(length(d.xz),0.04);
  float sc = n/6.2831853;
  vec2 p = vec2(a*sc, h*sc*0.45 + uTime*speed);
  vec2 id = floor(p); vec2 f = fract(p);
  float r = hash(vec2(id.x,seed));
  float x = abs(f.x-(0.25+0.5*r));
  float len = 0.45+0.45*hash(id+seed);
  float s = smoothstep(0.06,0.0,x)*(1.0-f.y/len)*step(f.y,len);
  return s*step(0.5,hash(id+seed*3.1));
}
float ripples(vec2 p){
  float s = 0.0;
  for(int k=0;k<2;k++){
    vec2 q = p*(1.4+float(k)*1.1); vec2 id = floor(q); vec2 f = fract(q)-0.5;
    float t = fract(uTime*0.7+hash(id+float(k)*7.0)); float r = length(f);
    s += sin((r-t*0.5)*42.0)*smoothstep(0.09,0.0,abs(r-t*0.5))*(1.0-t);
  }
  return s;
}
vec3 skyRain(vec3 d, float dark){
  float h = clamp(d.y,-1.0,1.0);
  vec3 col = mix(vec3(0.10,0.14,0.19), vec3(0.03,0.05,0.09), smoothstep(0.0,0.8,h))*dark;
  float cl = fbm(d.xz/max(d.y+0.35,0.2)*1.4 + uTime*0.02);
  col += vec3(0.07,0.09,0.12)*cl*smoothstep(-0.1,0.5,h)*dark;
  col += vec3(0.55,0.62,0.75)*uFlash*(0.4+cl);
  return col;
}
vec3 envRain(vec3 d, float storm){
  float dark = storm>0.5 ? 0.7 : 1.0;
  vec3 col;
  if (d.y < -0.01) {
    float t = 1.6/(-d.y); vec2 gp = d.xz*t;
    vec3 refl = skyRain(vec3(d.x,-d.y,d.z), dark);
    float rp = ripples(gp*0.5)*smoothstep(30.0,4.0,t);
    col = refl*0.8 + vec3(0.04,0.06,0.08) + vec3(0.25,0.3,0.38)*rp*0.35;
    col = mix(col, skyRain(vec3(d.x,0.0,d.z),dark), smoothstep(8.0,40.0,t));
  } else col = skyRain(d,dark);
  float fog = exp(-abs(d.y)*5.0);
  col = mix(col, vec3(0.12,0.16,0.21)*dark + vec3(0.4,0.45,0.55)*uFlash, fog*0.35);
  float n = (storm>0.5?1.6:1.0);
  float r = rainLayer(d,70.0*n,2.6,1.0)*0.55 + rainLayer(d,120.0*n,3.4,2.0)*0.4 + rainLayer(d,200.0*n,4.4,3.0)*0.3;
  col += vec3(0.65,0.75,0.9)*r*(0.35+uFlash);
  return col;
}
vec3 envSpace(vec3 d){
  vec3 col = vec3(0.005,0.006,0.015);
  vec2 a = vec2(atan(d.x,d.z), asin(clamp(d.y,-1.0,1.0)));
  float n1 = fbm(a*1.2+3.0), n2 = fbm(a*2.4-uTime*0.004);
  col += vec3(0.35,0.10,0.45)*pow(n1,2.5)*1.2 + vec3(0.05,0.2,0.45)*pow(n2,3.0)*1.0;
  vec3 p = d*70.0; vec3 id = floor(p); vec3 f = fract(p)-0.5;
  float h = hash3(id);
  float star = step(0.985,h)*smoothstep(0.3,0.0,length(f));
  col += vec3(0.9,0.9,1.0)*star*(0.5+0.5*sin(uTime*2.0+h*50.0));
  vec3 pc = normalize(vec3(0.6,0.25,-0.75)); float cd = dot(d,pc);
  float R = 0.97;
  if (cd > R) {
    float k = (cd-R)/(1.0-R); vec3 nn = normalize(d-pc*cd+pc*0.0);
    float lit = clamp(dot(normalize(vec3(d.x-pc.x,d.y-pc.y,d.z-pc.z)), normalize(vec3(-0.6,0.5,0.4)))*0.5+0.55,0.0,1.0);
    float bands = noise(vec2(d.y*60.0, 0.0))*0.5+0.5;
    col = mix(vec3(0.7,0.35,0.2),vec3(0.9,0.7,0.5),bands)*lit;
  } else col += vec3(0.4,0.6,1.0)*smoothstep(R-0.02,R,cd)*0.5;
  return col;
}
vec3 envAurora(vec3 d){
  vec3 col = mix(vec3(0.01,0.02,0.05),vec3(0.0,0.0,0.02),smoothstep(0.0,0.9,d.y));
  float a = atan(d.x,d.z);
  vec3 p = d*60.0; float h = hash3(floor(p)); col += vec3(1.0)*step(0.99,h)*smoothstep(0.35,0.0,length(fract(p)-0.5))*step(0.0,d.y);
  float au = 0.0; vec3 ac = vec3(0.0);
  for(int i=0;i<3;i++){
    float fi = float(i);
    float w = fbm(vec2(a*2.5+fi*3.0+uTime*0.05, uTime*0.08+fi));
    float yy = d.y*(2.2+fi*0.4) - 0.25 - w*0.5;
    float band = smoothstep(0.0,0.5,yy)*exp(-yy*2.5)*smoothstep(-0.05,0.1,d.y);
    float ray = 0.6+0.4*noise(vec2(a*40.0+fi*7.0, uTime*0.3));
    vec3 cc = mix(vec3(0.1,1.0,0.5),vec3(0.6,0.2,0.9),clamp(yy*1.2,0.0,1.0));
    ac += cc*band*ray*(0.5-fi*0.12);
  }
  col += ac;
  if (d.y<0.0) { float t = 1.5/(-d.y+0.001); vec2 g = d.xz*t;
    vec3 snow = vec3(0.55,0.65,0.8)*(0.25+0.1*noise(g*2.0)); col = mix(snow, vec3(0.02,0.04,0.07)+ac*0.4, smoothstep(5.0,40.0,t)); }
  return col;
}
vec3 envSunset(vec3 d){
  vec3 sun = normalize(vec3(0.25,0.06,-1.0));
  float h = d.y;
  vec3 col = mix(vec3(1.0,0.45,0.15),vec3(0.35,0.2,0.5),smoothstep(0.0,0.5,h));
  col = mix(col, vec3(0.08,0.07,0.2), smoothstep(0.3,0.9,h));
  float sd = dot(d,sun);
  col += vec3(1.0,0.8,0.5)*pow(max(sd,0.0),64.0) + vec3(1.0,0.6,0.3)*pow(max(sd,0.0),6.0)*0.4;
  col += vec3(1.0)*smoothstep(0.9985,0.9995,sd);
  float cl = fbm(d.xz/max(d.y+0.2,0.15)*1.5+uTime*0.01);
  col = mix(col, col*vec3(1.2,0.7,0.6), cl*smoothstep(0.0,0.4,h)*0.5);
  if (h<0.0) {
    float t = 8.0/(-h+0.001); vec2 g = d.xz*t*0.08;
    vec2 e = vec2(fbm(g+uTime*0.1), fbm(g*1.7-uTime*0.12));
    vec3 n = normalize(vec3((e.x-0.5)*0.35,1.0,(e.y-0.5)*0.35));
    vec3 rd = reflect(d,n); rd.y = abs(rd.y);
    float rs = dot(normalize(rd),sun);
    vec3 sky = mix(vec3(1.0,0.45,0.15),vec3(0.35,0.2,0.5),smoothstep(0.0,0.5,rd.y));
    col = sky*0.55 + vec3(1.0,0.7,0.4)*pow(max(rs,0.0),40.0)*1.2 + vec3(0.02,0.04,0.08);
    col = mix(col, vec3(1.0,0.5,0.2)*0.5, exp(-t*0.02)*0.2);
  }
  return col;
}
vec3 envCinema(vec3 d){
  vec3 col = vec3(0.012,0.012,0.02) + vec3(0.02,0.015,0.03)*smoothstep(-0.2,0.6,d.y);
  if (d.y<-0.02){ float t=1.6/(-d.y); vec2 g=d.xz*t; float rows=smoothstep(0.03,0.0,abs(fract(g.y*0.5)-0.5)-0.46); col += vec3(0.02,0.015,0.04)*rows*smoothstep(20.0,2.0,t); }
  float a = atan(d.x,d.z); col += vec3(0.05,0.0,0.0)*smoothstep(0.45,0.5,abs(fract(a*3.0)-0.5))*smoothstep(-0.3,0.3,d.y)*0.6;
  return col;
}
void main(){
  vec3 d = normalize(uRot*vec3(vNdc.x*uTan.x, vNdc.y*uTan.y, -1.0));
  vec3 col;
  if (uEnv==1) col = envRain(d,0.0);
  else if (uEnv==2) col = envRain(d,1.0);
  else if (uEnv==3) col = envSpace(d);
  else if (uEnv==4) col = envAurora(d);
  else if (uEnv==5) col = envSunset(d);
  else if (uEnv==6) col = envCinema(d);
  else if (uEnv==7) { vec2 uv = vec2(atan(d.x,-d.z)/6.2831853+0.5, 0.5-asin(clamp(d.y,-1.0,1.0))/3.14159265); col = texture2D(uPano,uv).rgb; }
  else col = vec3(0.02);
  gl_FragColor = vec4(col,1.0);
}
"""
}
