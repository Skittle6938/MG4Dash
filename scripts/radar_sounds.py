"""The speed camera alerter's extra sounds (24/11), synthesised here (no sample from anywhere else): each one short,
clear over road noise without being harsh, its loudness matched to the existing chime and beeps.
Writes app/src/main/res/raw/fx_radar_<name>.wav (16-bit mono, 22 050 Hz). Run: python scripts/radar_sounds.py"""
import array, math, os, sys, wave

RATE = 22050
RAW = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', 'raw')


def env(i, n, attack=0.005, release=0.08):
    """A soft attack and an exponential fall, so nothing clicks."""
    t = i / RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    return a * math.exp(-t / release) if release else a


def tone(freq, dur, harm=(1.0,), attack=0.005, release=0.15, vib=0.0, sweep=0.0):
    n = int(dur * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        f = freq * (1 + sweep * t / dur) * (1 + vib * math.sin(2 * math.pi * 6 * t))
        s = sum(h * math.sin(2 * math.pi * f * (k + 1) * t) for k, h in enumerate(harm))
        out.append(s * env(i, n, attack, release))
    return out


def silence(dur): return [0.0] * int(dur * RATE)


def square(freq, dur, release=0.05):
    n = int(dur * RATE)
    return [(1.0 if math.sin(2 * math.pi * freq * i / RATE) >= 0 else -1.0) * 0.5 * env(i, n, 0.001, release) for i in range(n)]


def mix(*parts):
    out = []
    for p in parts: out += p
    return out


def level(samples, target_rms):
    """Scaled to [target_rms], the peak kept under 90 % of full scale."""
    rms = math.sqrt(sum(s * s for s in samples) / len(samples)) or 1.0
    g = target_rms / rms
    peak = max(abs(s) for s in samples) * g
    if peak > 0.9 * 32767: g *= 0.9 * 32767 / peak
    return [int(max(-32767, min(32767, s * g))) for s in samples]


def rms_of(path):
    w = wave.open(path); d = array.array('h', w.readframes(w.getnframes()))
    return math.sqrt(sum(x * x for x in d) / len(d))


SOUNDS = {
    # Two soft wooden notes, a fifth apart
    'marimba': mix(tone(784, 0.16, (1, 0.25, 0.08), release=0.07), tone(1175, 0.30, (1, 0.25, 0.08), release=0.09)),
    # A rising ding-dong
    'dingdong': mix(tone(659, 0.28, (1, 0.4, 0.15), release=0.12), tone(880, 0.45, (1, 0.4, 0.15), release=0.16)),
    # A sonar ping: one round note sliding down a little, a long soft tail
    'sonar': tone(1320, 0.85, (1, 0.12), attack=0.003, release=0.22, sweep=-0.06),
    # Three gentle pulses
    'pulse': mix(*[p for _ in range(3) for p in (tone(988, 0.09, (1, 0.3), release=0.05), silence(0.07))]),
    # A retro 8-bit blip, two quick steps up
    'blip': mix(square(880, 0.07, 0.04), square(1320, 0.11, 0.05)),
    # A low warning hum, a little vibrato, for the strong alert
    'hum': tone(330, 0.75, (1, 0.6, 0.35, 0.2), attack=0.02, release=0.4, vib=0.012),
}

if __name__ == '__main__':
    refs = [os.path.join(RAW, f'fx_radar_{n}.wav') for n in ('chime', 'light', 'strong')]
    target = sum(rms_of(r) for r in refs if os.path.exists(r)) / max(1, sum(os.path.exists(r) for r in refs)) or 5000
    for name, s in SOUNDS.items():
        assert len(s) / RATE < 1.0, name
        data = array.array('h', level(s, target))
        with wave.open(os.path.join(RAW, f'fx_radar_{name}.wav'), 'wb') as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(data.tobytes())
        print(name, f'{len(s) / RATE:.2f} s')
    print('loudness matched to', round(target))
