#!/usr/bin/env python3
"""Synthesizes the panel's sound effects into app/src/main/res/raw/snd_*.ogg (needs numpy and ffmpeg with libvorbis).
Run from the project root: python3 tools/gen_sounds.py"""
import numpy as np, subprocess, os, wave
SR=44100
raw='app/src/main/res/raw'; os.makedirs(raw,exist_ok=True)
rng=np.random.default_rng(7)
def t_(dur): return np.arange(int(SR*dur))/SR
def env(t,a=0.001,d=0.05): 
    e=np.minimum(1,t/a)*np.exp(-np.maximum(0,t-a)/d); return e
def lp(x,fc):
    # simple one-pole lowpass applied twice
    a=np.exp(-2*np.pi*fc/SR); y=np.zeros_like(x); s=0
    for _ in range(2):
        y=np.zeros_like(x); s=0
        for i,v in enumerate(x): s=(1-a)*v+a*s; y[i]=s
        x=y
    return y
def hp(x,fc): return x-lp(x,fc)
def bell(f,dur,decay,parts=((1,1),(2.76,0.35),(5.4,0.12),(8.9,0.05))):
    t=t_(dur); y=sum(a*np.sin(2*np.pi*f*m*t)*np.exp(-t/(decay/(1+0.6*m))) for m,a in parts)
    return y*np.minimum(1,t/0.002)
def fadeout(y,ms=8):
    n=int(SR*ms/1000); y=y.copy(); y[-n:]*=np.linspace(1,0,n); return y
def norm(y,peak): return y/np.max(np.abs(y))*peak
def pad(y,ms=3): return np.concatenate([y,np.zeros(int(SR*ms/1000))])
S={}
# tick: tiny high click
t=t_(0.018); S['tick']=norm(np.sin(2*np.pi*3800*t)*env(t,0.0004,0.0025)+0.5*hp(rng.normal(0,1,len(t)),2500)*env(t,0.0002,0.0012),0.32)
# click: tap, fuller
t=t_(0.03); S['click']=norm(np.sin(2*np.pi*2100*t)*env(t,0.0005,0.004)+0.35*np.sin(2*np.pi*1100*t)*env(t,0.0008,0.006)+0.3*hp(rng.normal(0,1,len(t)),1800)*env(t,0.0002,0.0015),0.36)
# detent: low tick
t=t_(0.025); S['detent']=norm(np.sin(2*np.pi*950*t)*env(t,0.0006,0.005)+0.25*lp(rng.normal(0,1,len(t)),3000)*env(t,0.0002,0.002),0.3)
# snap: soft thock for page snap
t=t_(0.05); S['snap']=norm(np.sin(2*np.pi*(1500-500*t/0.05)*t)*env(t,0.0006,0.007)+0.5*np.sin(2*np.pi*620*t)*env(t,0.001,0.01),0.3)
# ring: two-note chime
a=bell(1318.5,0.42,0.22); b=bell(1975.5,0.42,0.25); y=np.zeros(int(SR*0.5)); y[:len(a)]+=a; o=int(SR*0.075); y[o:o+len(b)]+=0.85*b
S['ring']=norm(fadeout(y,30),0.42)
# vibrate: two soft buzz pulses
t=t_(0.24); car=np.sign(np.sin(2*np.pi*165*t))*0.6+np.sin(2*np.pi*330*t)*0.4
gate=((t%0.11)<0.07).astype(float); gate=lp(gate,60)
S['vibrate']=norm(lp(car*gate,1400)*np.minimum(1,(0.24-t)/0.02),0.34)

def mix(dur,*items):
    y=np.zeros(int(SR*dur))
    for off,sig,g in items:
        o=int(SR*off); n=min(len(sig),len(y)-o); y[o:o+n]+=g*sig[:n]
    return y
a=bell(880,0.22,0.06,((1,1),(2.0,0.2))); b=bell(587.3,0.3,0.08,((1,1),(2.0,0.2)))
S['silent']=norm(fadeout(lp(mix(0.36,(0,a,1),(0.07,b,1)),2500),20),0.36)
t=t_(0.34); f=700*np.exp(np.log(0.5)*t/0.34); ph=2*np.pi*np.cumsum(f)/SR
S['dnd_on']=norm(fadeout((np.sin(ph)+0.25*np.sin(2*ph))*np.minimum(1,t/0.03)*np.exp(-t/0.16),25),0.34)
t=t_(0.3); f=380*np.exp(np.log(2)*t/0.3); ph=2*np.pi*np.cumsum(f)/SR
S['dnd_off']=norm(fadeout((np.sin(ph)+0.25*np.sin(2*ph))*np.minimum(1,t/0.02)*np.exp(-t/0.14),25),0.34)
t=t_(0.09); f=420+900*(1-np.exp(-t/0.012)); ph=2*np.pi*np.cumsum(f)/SR
S['pop']=norm(fadeout(np.sin(ph)*env(t,0.002,0.025),10),0.36)
t=t_(0.07)
def blip(f,d=0.06): tt=t_(d); return np.sin(2*np.pi*f*tt)*env(tt,0.001,0.012)
S['toggle_on']=norm(mix(0.08,(0,blip(1250),1),(0.025,blip(1870),0.6)),0.34)
S['toggle_off']=norm(mix(0.08,(0,blip(1600),1),(0.025,blip(1050),0.6)),0.32)
t=t_(0.16); n=rng.normal(0,1,len(t)); sh=hp(lp(n,6000),900)
e=env(t,0.0005,0.012)+0.8*env(np.maximum(t-0.07,0),0.0005,0.018)*(t>0.07)
S['shutter']=norm(sh*e+0.4*np.sin(2*np.pi*400*t)*env(t,0.001,0.01),0.4)
t=t_(0.14); n=lp(hp(rng.normal(0,1,len(t)),1500),5000); S['open']=norm(n*np.sin(np.pi*t/0.14)**2,0.12)
sizes={}
for k,y in S.items():
    y=pad(y.astype(np.float32))
    wavp=f'/tmp/{k}.wav'; pcm=(np.clip(y,-1,1)*32767).astype(np.int16)
    with wave.open(wavp,'wb') as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    out=f'{raw}/snd_{k}.ogg'
    r=subprocess.run(['ffmpeg','-y','-loglevel','error','-i',wavp,'-c:a','libvorbis','-q:a','3',out],capture_output=True,text=True,timeout=60)
    sizes[k]=os.path.getsize(out) if not r.stderr else r.stderr
print(sizes)