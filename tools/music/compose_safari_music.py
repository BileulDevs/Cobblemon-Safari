#!/usr/bin/env python3
"""
Composes and renders the Safari's original music, one track per zone.

Why code instead of audio files made by hand:
  * Every note is written here, so the music is original and free of rights:
    nothing is sampled, nothing is copied from a game soundtrack.
  * A track can be retuned (tempo, key, instruments, length) by editing a
    table and running the script again, without any audio software.

Usage (from the repository root; needs numpy, scipy and ffmpeg with libvorbis):
    python3 tools/music/compose_safari_music.py            # every track
    python3 tools/music/compose_safari_music.py plains     # one track

Output: common/src/main/resources/assets/cobblemon_safari/sounds/music/<zone>.ogg
The sound events pointing at these files are written by
tools/worldgen/generate_worldgen.py (write_sounds), which also decides which
biome plays which zone. The rendering is deterministic: the same script gives
the same notes and the same audio, so regenerating does not churn the repository
unless something was changed.

How a track is built:
  1. A theme (THEMES) gives a key, a tempo, a meter, chord progressions per
     section and the instruments of each role.
  2. The melody is generated from a seeded random walk on the scale, with
     strong beats snapped to chord tones and phrases ending on cadences. The
     same seed always gives the same tune.
  3. Accompaniment (arpeggios, strums, bass, pad, drums) follows patterns.
  4. Each instrument is synthesised (additive, FM, Karplus-Strong), mixed on
     buses with a convolution reverb, then normalised and encoded to Ogg Vorbis.
"""

import os
import subprocess
import sys
import tempfile
import wave

import numpy as np
from scipy import signal

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "cobblemon_safari", "sounds", "music")

# Rendering rate. 44.1 kHz is what Minecraft's own music uses; lower rates
# shrink the files but dull the bells and shakers.
SAMPLE_RATE = 44100

# Vorbis quality (-q:a). 2 is about 96 kbit/s stereo: transparent enough for
# soft synthesised music. Each step up adds roughly 15% to the jar size.
VORBIS_QUALITY = 2

# Loudness target of the finished track, as RMS in dBFS, and peak ceiling.
# Vanilla music sits around -20 dBFS RMS; louder would make the Safari jump
# out against the rest of the game, quieter would force players to turn up
# the Music slider.
TARGET_RMS_DB = -20.0
PEAK_CEILING = 0.89

# Fade at the very start, in seconds, so a track never starts with a click.
FADE_IN_SECONDS = 1.0
# Ring-out kept after the last bar so the final chord and the reverb tail die
# out naturally instead of being cut.
TAIL_SECONDS = 4.0

# Human feel: random timing offset (seconds) and velocity spread of each note.
# Zero sounds mechanical; much more sounds sloppy.
TIMING_JITTER = 0.008
VELOCITY_JITTER = 0.08

A4_MIDI = 69
A4_HZ = 440.0

SCALES = {
    "major": [0, 2, 4, 5, 7, 9, 11],
    "minor": [0, 2, 3, 5, 7, 8, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    # Phrygian dominant: the "desert" colour, with its augmented second.
    "phrygian_dominant": [0, 1, 4, 5, 7, 8, 10],
}


def midi_to_hz(midi):
    """Frequency of a MIDI note number."""
    return A4_HZ * 2.0 ** ((midi - A4_MIDI) / 12.0)


# ---------------------------------------------------------------------------
# Envelopes and small DSP helpers
# ---------------------------------------------------------------------------

def time_axis(seconds):
    """Sample times of a sound of the given length."""
    return np.arange(int(seconds * SAMPLE_RATE)) / SAMPLE_RATE


def adsr(n, attack, decay, sustain, release, hold):
    """
    Attack-decay-sustain-release envelope over n samples. The note is held for
    `hold` seconds (attack and decay included), then released.
    """
    t = np.arange(n) / SAMPLE_RATE
    env = np.empty(n)
    a = max(attack, 1e-4)
    env = np.where(t < a, t / a, 1.0)
    in_decay = (t >= a) & (t < a + decay)
    env = np.where(in_decay, 1.0 - (1.0 - sustain) * (t - a) / max(decay, 1e-4), env)
    env = np.where(t >= a + decay, sustain, env)
    rel = t >= hold
    level_at_release = np.interp(hold, t, env) if n else 0.0
    env = np.where(rel, level_at_release * np.exp(-(t - hold) / max(release, 1e-4) * 3.0), env)
    return env


def lowpass(x, cutoff, order=2):
    """Butterworth low-pass, used to soften bright synthesis."""
    cutoff = min(cutoff, SAMPLE_RATE * 0.45)
    sos = signal.butter(order, cutoff, "low", fs=SAMPLE_RATE, output="sos")
    return signal.sosfilt(sos, x)


def highpass(x, cutoff, order=2):
    """Butterworth high-pass, used for shakers and to clean rumble."""
    sos = signal.butter(order, cutoff, "high", fs=SAMPLE_RATE, output="sos")
    return signal.sosfilt(sos, x)


def bandpass(x, low, high, order=2):
    """Butterworth band-pass, used for drum skins and breath noise."""
    sos = signal.butter(order, [low, min(high, SAMPLE_RATE * 0.45)], "band", fs=SAMPLE_RATE, output="sos")
    return signal.sosfilt(sos, x)


# ---------------------------------------------------------------------------
# Instruments. Each returns a mono array; `dur` is the held length in seconds,
# the returned sound may ring longer.
# ---------------------------------------------------------------------------

def flute(freq, dur, vel, rng):
    """Breathy flute: near-sine tone, delayed vibrato, a little breath noise."""
    total = dur + 0.25
    t = time_axis(total)
    vibrato_depth = np.clip((t - 0.25) / 0.4, 0, 1) * 0.0045
    inst = freq * (1 + vibrato_depth * np.sin(2 * np.pi * 5.1 * t))
    phase = 2 * np.pi * np.cumsum(inst) / SAMPLE_RATE
    tone = np.sin(phase) + 0.16 * np.sin(2 * phase) + 0.05 * np.sin(3 * phase)
    breath = bandpass(rng.standard_normal(len(t)), freq * 1.5, freq * 4) * 0.05
    env = adsr(len(t), 0.06, 0.2, 0.85, 0.12, dur)
    return (tone + breath) * env * vel * 0.5


def pluck(freq, dur, vel, rng, brightness=3500.0, sustain=1.6):
    """
    Plucked string (Karplus-Strong). `brightness` is the cutoff of the pluck
    noise; `sustain` the time in seconds to fade by 60 dB when let ring.
    """
    total = dur + 0.6
    n = int(total * SAMPLE_RATE)
    period = max(2, int(round(SAMPLE_RATE / freq)))
    burst = lowpass(rng.uniform(-1, 1, period), brightness)
    excitation = np.zeros(n)
    excitation[:period] = burst
    # Per-period feedback giving a 60 dB fade in `sustain` seconds.
    feedback = 10 ** (-3.0 / (sustain * freq))
    a = np.zeros(period + 2)
    a[0] = 1.0
    a[period] = -0.5 * feedback
    a[period + 1] = -0.5 * feedback
    out = signal.lfilter([1.0], a, excitation)
    damp = adsr(n, 0.001, 0.0, 1.0, 0.15, dur + 0.3)
    return out * damp * vel * 0.9


def mallet(freq, dur, vel, rng):
    """Marimba-like mallet: fundamental plus the tuned fourth-harmonic bar mode."""
    t = time_axis(min(dur, 1.2) + 1.0)
    body = np.sin(2 * np.pi * freq * t) * np.exp(-t / 0.55)
    overtone = 0.3 * np.sin(2 * np.pi * freq * 3.93 * t) * np.exp(-t / 0.07)
    click = 0.05 * bandpass(rng.standard_normal(len(t)), 2000, 6000) * np.exp(-t / 0.004)
    attack = np.clip(t / 0.002, 0, 1)
    return (body + overtone + click) * attack * vel * 0.6


def bell(freq, dur, vel, rng):
    """Glassy FM bell with an inharmonic modulator, for snow and caves."""
    t = time_axis(max(dur, 0.5) + 2.5)
    index = 2.2 * np.exp(-t / 0.35) + 0.3
    mod = np.sin(2 * np.pi * freq * 3.5 * t)
    tone = np.sin(2 * np.pi * freq * t + index * mod)
    shimmer = 0.15 * np.sin(2 * np.pi * freq * 2.76 * t) * np.exp(-t / 0.6)
    env = np.exp(-t / 1.5) * np.clip(t / 0.003, 0, 1)
    return (tone + shimmer) * env * vel * 0.45


def celesta(freq, dur, vel, rng):
    """Music-box celesta: bright, short, harmonic. The Pokémon Center lead."""
    t = time_axis(min(dur, 0.8) + 1.0)
    tone = (np.sin(2 * np.pi * freq * t)
            + 0.35 * np.sin(2 * np.pi * freq * 4 * t) * np.exp(-t / 0.12)
            + 0.12 * np.sin(2 * np.pi * freq * 2 * t))
    env = np.exp(-t / 0.7) * np.clip(t / 0.002, 0, 1)
    return tone * env * vel * 0.45


def epiano(freq, dur, vel, rng):
    """Electric piano (FM, ratio 1) with a short tine, for chords."""
    t = time_axis(dur + 0.4)
    index = 1.1 * np.exp(-t / 0.25) + 0.25
    tone = np.sin(2 * np.pi * freq * t + index * np.sin(2 * np.pi * freq * t))
    tine = 0.08 * np.sin(2 * np.pi * freq * 7 * t) * np.exp(-t / 0.03)
    env = adsr(len(t), 0.004, 1.2, 0.35, 0.18, dur)
    return (tone + tine) * env * vel * 0.4


def kalimba(freq, dur, vel, rng):
    """Thumb piano: soft sine with a brief metallic tine partial."""
    t = time_axis(min(dur, 1.0) + 0.9)
    tone = np.sin(2 * np.pi * freq * t) * np.exp(-t / 0.6)
    tine = 0.25 * np.sin(2 * np.pi * freq * 5.95 * t) * np.exp(-t / 0.04)
    return (tone + tine) * np.clip(t / 0.002, 0, 1) * vel * 0.55


def bass(freq, dur, vel, rng):
    """Round bass: sine with two soft harmonics and a short pluck envelope."""
    t = time_axis(dur + 0.15)
    phase = 2 * np.pi * freq * t
    tone = np.sin(phase) + 0.3 * np.sin(2 * phase) + 0.08 * np.sin(3 * phase)
    env = adsr(len(t), 0.006, 0.3, 0.6, 0.06, dur)
    return lowpass(tone * env, 900) * vel * 0.7


def saw(freq, t, detune_cents):
    """Band-limited sawtooth by additive synthesis (pads only, so few harmonics)."""
    f = freq * 2 ** (detune_cents / 1200.0)
    harmonics = max(1, min(14, int(SAMPLE_RATE * 0.45 / f)))
    out = np.zeros(len(t))
    for k in range(1, harmonics + 1):
        out += np.sin(2 * np.pi * f * k * t) / k
    return out


def pad_note(freq, dur, vel, rng, cutoff=1400.0):
    """Warm pad voice: three detuned saws, low-passed, slow attack and release."""
    t = time_axis(dur + 1.6)
    tone = saw(freq, t, -7) + saw(freq, t, 0) + saw(freq, t, 7)
    env = adsr(len(t), 0.9, 0.5, 0.85, 1.2, dur)
    return lowpass(tone, cutoff) * env * vel * 0.06


def drone_note(freq, dur, vel, rng):
    """Dark pad for caves: like the pad, but closed down to a murmur."""
    return pad_note(freq, dur, vel, rng, cutoff=550.0) * 1.4


def kick(vel, rng):
    """Soft kick: falling sine thump."""
    t = time_axis(0.4)
    freq = 50 + 70 * np.exp(-t / 0.04)
    phase = 2 * np.pi * np.cumsum(freq) / SAMPLE_RATE
    return np.sin(phase) * np.exp(-t / 0.16) * vel * 0.8


def hand_drum(vel, rng, pitch=190.0):
    """Conga-like hand drum: pitched skin plus a slap of noise."""
    t = time_axis(0.35)
    freq = pitch * (1 + 0.15 * np.exp(-t / 0.02))
    phase = 2 * np.pi * np.cumsum(freq) / SAMPLE_RATE
    skin = np.sin(phase) * np.exp(-t / 0.13)
    slap = bandpass(rng.standard_normal(len(t)), 800, 3500) * np.exp(-t / 0.015) * 0.3
    return (skin + slap) * vel * 0.55


def frame_drum(vel, rng, deep=True):
    """Frame drum: 'doum' (deep, centre) or 'tek' (rim, bright)."""
    t = time_axis(0.45 if deep else 0.15)
    if deep:
        tone = np.sin(2 * np.pi * 85 * t) * np.exp(-t / 0.2)
        skin = lowpass(rng.standard_normal(len(t)), 400) * np.exp(-t / 0.05) * 0.4
        return (tone + skin) * vel * 0.7
    rim = bandpass(rng.standard_normal(len(t)), 1800, 6000) * np.exp(-t / 0.02)
    return (rim + 0.3 * np.sin(2 * np.pi * 420 * t) * np.exp(-t / 0.03)) * vel * 0.35


def shaker(vel, rng):
    """Shaker: high-passed noise with a short swell."""
    t = time_axis(0.09)
    env = np.sin(np.pi * np.clip(t / 0.09, 0, 1)) ** 2
    return highpass(rng.standard_normal(len(t)), 6000) * env * vel * 0.12


def rim(vel, rng):
    """Rim click: short woody tick."""
    t = time_axis(0.08)
    tone = np.sin(2 * np.pi * 1700 * t) * np.exp(-t / 0.012)
    noise = bandpass(rng.standard_normal(len(t)), 2000, 5000) * np.exp(-t / 0.006) * 0.4
    return (tone + noise) * vel * 0.25


INSTRUMENTS = {
    "flute": flute,
    "pluck": pluck,
    "bright_pluck": lambda f, d, v, r: pluck(f, d, v, r, brightness=6000.0, sustain=1.0),
    "mallet": mallet,
    "bell": bell,
    "celesta": celesta,
    "epiano": epiano,
    "kalimba": kalimba,
    "bass": bass,
    "pluck_bass": lambda f, d, v, r: lowpass(pluck(f, d, v, r, brightness=1200.0, sustain=1.2), 1000),
    "pad": pad_note,
    "drone": drone_note,
}

# Drum letters used in drum pattern strings (one character per sixteenth;
# "." is silence). Upper case is accented.
DRUMS = {
    "k": kick,
    "c": lambda v, r: hand_drum(v, r, 190.0),
    "l": lambda v, r: hand_drum(v, r, 135.0),
    "d": lambda v, r: frame_drum(v, r, True),
    "t": lambda v, r: frame_drum(v, r, False),
    "s": shaker,
    "r": rim,
}


# ---------------------------------------------------------------------------
# Themes
# ---------------------------------------------------------------------------
# Chord symbols are scale degrees, 0-based (0 = tonic chord, 4 = dominant...),
# one per bar. Sections: intro, A, A2 (A varied, fuller), B (contrast), A3
# (A again), outro. Each A/B section is two 4-bar phrases.
#
# rhythms: melody rhythm cells for one bar, in beats; negative = rest.
# cadence: rhythm of the last bar of each phrase.
# lead / lead_b: melody instrument in A sections and in B.
# double: instrument doubling the melody an octave up in A2 (None: none).
# accompaniment: "arp" (broken chords), "strum" (plucked chords),
#   "comp" (off-beat chords), "sparse" (rare chord tones, caves).
# acc_pattern: for arp, chord-tone indices per step; for strum/comp, the beats hit.
# drums: per section name, a pattern string of sixteenths for one bar.

THEMES = {
    "plains": {
        "seed": 1101, "scale": "major", "tonic": 55, "bpm": 100, "meter": 4,
        "melody_range": (67, 88),
        "progression_a": [0, 4, 5, 3, 0, 3, 4, 0],
        "progression_b": [5, 3, 0, 4, 5, 3, 1, 4],
        "rhythms": [[1, 0.5, 0.5, 1, 1], [1.5, 0.5, 1, 1], [0.5, 0.5, 1, 0.5, 0.5, 1], [2, 1, 1]],
        "cadence": [1, 1, 2],
        "lead": "flute", "lead_b": "kalimba", "double": "kalimba",
        "accompaniment": "strum", "acc_instrument": "pluck", "acc_pattern": [0, 1.5, 2, 3],
        "acc_octave": 0, "bass": "bass", "bass_pattern": [(0, 1.5, 0), (1.5, 0.5, 4), (2, 1.5, 0), (3.5, 0.5, 2)],
        "pad": True,
        "drums": {"A2": "k.s.s.s.k.s.s.s.", "B": "k.s.s.s.k.s.ssss", "A3": "k.s.s.s.k.s.s.s."},
    },
    "forest": {
        "seed": 2203, "scale": "dorian", "tonic": 50, "bpm": 88, "meter": 4,
        "melody_range": (62, 81),
        "progression_a": [0, 3, 0, 6, 0, 3, 6, 0],
        "progression_b": [2, 6, 3, 0, 2, 6, 3, 4],
        "rhythms": [[1, 1, 0.5, 0.5, 1], [0.5, 0.5, 0.5, 0.5, 2], [1.5, 0.5, -1, 1], [1, 0.5, 0.5, 2]],
        "cadence": [1, 3],
        "lead": "mallet", "lead_b": "flute", "double": None,
        "accompaniment": "arp", "acc_instrument": "mallet", "acc_pattern": [0, 1, 2, 1, 3, 2, 1, 2],
        "acc_octave": -12, "bass": "pluck_bass", "bass_pattern": [(0, 1.5, 0), (1.5, 1, 0), (3, 1, 4)],
        "pad": True,
        "drums": {"A2": "c...l.c.c...l...", "B": "c..cl.c.c..cl.l.", "A3": "c...l.c.c...l..."},
    },
    "arid": {
        "seed": 3307, "scale": "phrygian_dominant", "tonic": 52, "bpm": 96, "meter": 4,
        "melody_range": (64, 83),
        "progression_a": [0, 1, 0, 1, 0, 6, 1, 0],
        "progression_b": [3, 6, 1, 0, 3, 6, 1, 1],
        "rhythms": [[0.5, 0.5, 1, 0.5, 0.5, 1], [1, 0.5, 0.5, 2], [0.75, 0.25, 1, 1, 1], [1.5, 0.5, 0.5, 0.5, 1]],
        "cadence": [0.5, 0.5, 3],
        "lead": "bright_pluck", "lead_b": "flute", "double": "kalimba",
        "accompaniment": "arp", "acc_instrument": "kalimba", "acc_pattern": [0, 2, 1, 2, 0, 2, 1, 2],
        "acc_octave": -12, "bass": "pluck_bass", "bass_pattern": [(0, 0.75, 0), (0.75, 0.75, 0), (1.5, 1, 0), (3, 1, 4)],
        "pad": True,
        "drums": {"A": "d.....t.d.t.....", "A2": "d..t..t.d.t.t...", "B": "d..t..t.d.t.tt.t", "A3": "d..t..t.d.t.t..."},
    },
    "snow": {
        "seed": 4409, "scale": "minor", "tonic": 57, "bpm": 72, "meter": 3,
        "melody_range": (69, 91),
        "progression_a": [0, 5, 2, 6, 0, 5, 3, 4],
        "progression_b": [5, 6, 0, 0, 3, 5, 6, 6],
        "rhythms": [[1, 1, 1], [2, 1], [1.5, 0.5, 1], [1, 2]],
        "cadence": [3],
        "lead": "bell", "lead_b": "flute", "double": "celesta",
        "accompaniment": "arp", "acc_instrument": "epiano", "acc_pattern": [0, 1, 2, 3, 2, 1],
        "acc_octave": -12, "bass": "bass", "bass_pattern": [(0, 3, 0)],
        "pad": True,
        "drums": {},
    },
    "caves": {
        "seed": 5501, "scale": "minor", "tonic": 48, "bpm": 64, "meter": 4,
        "melody_range": (60, 79),
        "progression_a": [0, 0, 5, 5, 3, 3, 0, 0],
        "progression_b": [5, 6, 0, 0, 3, 3, 4, 4],
        "rhythms": [[2, -2], [-1, 1, 2], [3, -1], [-2, 1, 1]],
        "cadence": [4],
        "lead": "bell", "lead_b": "mallet", "double": None,
        "accompaniment": "sparse", "acc_instrument": "bell", "acc_pattern": [],
        "acc_octave": 12, "bass": "drone", "bass_pattern": [(0, 4, 0)],
        "pad": "drone",
        "drums": {},
    },
    "center": {
        "seed": 6607, "scale": "major", "tonic": 53, "bpm": 112, "meter": 4,
        "melody_range": (69, 89),
        "progression_a": [0, 5, 1, 4, 0, 5, 3, 4],
        "progression_b": [3, 4, 2, 5, 1, 4, 0, 0],
        "rhythms": [[0.5, 0.5, 1, 1, 1], [1, 0.5, 0.5, 1, 1], [0.5, 0.5, 0.5, 0.5, 2], [1.5, 0.5, 2]],
        "cadence": [1, 1, 2],
        "lead": "celesta", "lead_b": "kalimba", "double": "bell",
        "accompaniment": "comp", "acc_instrument": "epiano", "acc_pattern": [0.5, 1.5, 2.5, 3.5],
        "acc_octave": 0, "bass": "bass", "bass_pattern": [(0, 1, 0), (1, 1, 4), (2, 1, 0), (3, 1, 2)],
        "pad": False,
        "drums": {"A": "k...r...k...r...", "A2": "k.s.r.s.k.s.r.s.", "B": "k.s.r.s.k.skr.s.", "A3": "k.s.r.s.k.s.r.s."},
    },
}

INTRO_BARS = 2
OUTRO_BARS = 2

# Share of notes moved when a phrase repeats a motif. Higher varies the tune
# more and makes it less memorable; 0 repeats it exactly.
VARIATION_RATE = 0.4

# Distance from the middle of the melody range (in scale steps) beyond which
# the melody is pulled back toward it. Smaller keeps tunes narrow and samey;
# larger lets them wander to the edges.
GRAVITY_SPAN = 3


# ---------------------------------------------------------------------------
# Music theory
# ---------------------------------------------------------------------------

class Key:
    """A scale on a tonic, with conversions between scale steps and MIDI notes."""

    def __init__(self, scale_name, tonic):
        self.steps = SCALES[scale_name]
        self.tonic = tonic

    def midi(self, index):
        """MIDI note of a scale index (0 = tonic, 7 = tonic an octave up, -1 = below)."""
        octave, degree = divmod(index, 7)
        return self.tonic + 12 * octave + self.steps[degree]

    def chord_degrees(self, root, sevenths=False):
        """Scale degrees (mod 7) of the chord built in thirds on a degree."""
        degrees = [root % 7, (root + 2) % 7, (root + 4) % 7]
        if sevenths:
            degrees.append((root + 6) % 7)
        return degrees

    def index_near(self, midi):
        """Scale index whose note is closest to a MIDI note."""
        best, best_dist = 0, 1e9
        base = (midi - self.tonic) // 12 * 7
        for index in range(base - 8, base + 16):
            dist = abs(self.midi(index) - midi)
            if dist < best_dist:
                best, best_dist = index, dist
        return best


def voice_chord(key, root, previous, low, high, sevenths=False):
    """
    MIDI notes of a chord voicing within [low, high], as close as possible to
    the previous voicing (smooth voice leading, no jumping block chords).
    """
    degrees = key.chord_degrees(root, sevenths)
    pitch_classes = [(key.tonic + key.steps[d]) % 12 for d in degrees]
    candidates = []
    for bottom in range(low, high - 6):
        if bottom % 12 not in pitch_classes:
            continue
        notes = [bottom]
        for pc in pitch_classes:
            if pc == bottom % 12:
                continue
            n = bottom + ((pc - bottom) % 12)
            notes.append(n)
        notes.sort()
        if notes[-1] <= high:
            candidates.append(notes)
    if not candidates:
        return [key.tonic + 12 + key.steps[d] for d in degrees]
    if previous is None:
        mid = (low + high) / 2
        return min(candidates, key=lambda c: abs(sum(c) / len(c) - mid))
    return min(candidates, key=lambda c: sum(abs(a - b) for a, b in zip(sorted(c), sorted(previous)))
               + abs(len(c) - len(previous)) * 12)


# ---------------------------------------------------------------------------
# Melody
# ---------------------------------------------------------------------------

def phrase_melody(theme, key, chords, rng, final_tonic, motif=None):
    """
    One 4-bar phrase: bars 1-2 a motif, bar 3 the motif again moved to fit
    its chord (a sequence), bar 4 a cadence. Returns (notes, motif) where each
    note is (beat_from_phrase_start, length_beats, scale_index).
    """
    meter = theme["meter"]
    low = key.index_near(theme["melody_range"][0])
    high = key.index_near(theme["melody_range"][1])
    centre = (low + high) // 2

    def chord_tone_near(index, chord_root):
        tones = key.chord_degrees(chord_root)
        options = [i for i in range(index - 4, index + 5) if i % 7 in tones and low <= i <= high]
        return min(options, key=lambda i: abs(i - index)) if options else index

    def walk(rhythm, start_index, chord_root):
        notes, beat, index = [], 0.0, start_index
        last_step = 0
        for length in rhythm:
            if length < 0:
                beat += -length
                continue
            strong = abs(beat - round(beat)) < 1e-6 and int(round(beat)) % (2 if meter == 4 else 3) == 0
            if notes:
                if abs(last_step) >= 3:
                    step = -int(np.sign(last_step))  # recover a leap by a step back
                else:
                    step = int(rng.choice([-2, -1, -1, 0, 1, 1, 2, 3, -3], p=[.08, .25, .1, .07, .25, .1, .08, .035, .035]))
                    # Pull back toward the middle of the range: an unbiased
                    # walk drifts to an edge and then repeats the same note.
                    if (index - centre) * step > 0 and abs(index - centre) >= GRAVITY_SPAN and rng.random() < 0.6:
                        step = -step
                index = index + step
                if index > high or index < low:
                    index -= 2 * step
            if strong:
                index = chord_tone_near(index, chord_root)
            index = int(np.clip(index, low, high))
            last_step = index - (notes[-1][2] if notes else index)
            notes.append((beat, length, index))
            beat += length
        return notes

    if motif is None:
        r1 = theme["rhythms"][rng.integers(len(theme["rhythms"]))]
        r2 = theme["rhythms"][rng.integers(len(theme["rhythms"]))]
        bar1 = walk(r1, chord_tone_near(centre + int(rng.integers(-2, 3)), chords[0]), chords[0])
        bar2 = walk(r2, bar1[-1][2], chords[1])
        motif = (bar1, bar2)
    else:
        bar1 = [(b, l, chord_tone_near(i, chords[0]) if b == 0 else i) for b, l, i in motif[0]]
        bar2 = list(motif[1])
        # Variation: about 40% of the second bar's notes move to a neighbour,
        # so the answering phrase is recognisably the same tune, not a copy.
        bar2 = [(b, l, int(np.clip(i + rng.choice([-1, 1]), low, high)) if rng.random() < VARIATION_RATE else i)
                for b, l, i in bar2]

    # Bar 3: bar 1's shape, moved (a sequence) so it starts on a tone of bar
    # 3's chord. Nudging the start first avoids a plain repeat when bar 1's
    # first note already fits that chord.
    # The first shift keeping the whole bar inside the range wins: a clipped
    # sequence would flatten into repeated notes.
    start = bar1[0][2]
    shifts = [chord_tone_near(start + int(n), chords[2]) - start for n in rng.permutation([-2, -1, 1, 2])]
    shifts += [s + octave for s in shifts for octave in (-7, 7)] + [0]
    shift = next((s for s in shifts if all(low <= i + s <= high for _, _, i in bar1)), 0)
    bar3 = [(b, l, int(np.clip(i + shift, low, high))) for b, l, i in bar1]

    # Bar 4: cadence, landing on the tonic or (half cadence) the chord's third/fifth.
    cadence = theme["cadence"]
    approach = bar3[-1][2]
    tones = [0] if final_tonic else key.chord_degrees(chords[3])[1:]
    targets = [i for i in range(low, high + 1) if i % 7 in tones]
    target = min(targets, key=lambda i: abs(i - approach)) if targets else approach
    sounding = len([x for x in cadence if x > 0])
    # Step toward the target from the side the melody comes from, unless
    # that side would leave the range.
    direction = 1 if approach > target else -1
    if not low <= target + (sounding - 1) * direction <= high:
        direction = -direction
    bar4, beat = [], 0.0
    for k, length in enumerate(cadence):
        if length < 0:
            beat += -length
            continue
        remaining = len([x for x in cadence[k:] if x > 0])
        # Step toward the target, arriving on the last (long) note.
        index = target + (remaining - 1) * direction
        bar4.append((beat, length, int(np.clip(index, low, high))))
        beat += length

    notes = []
    for bar, content in enumerate((bar1, bar2, bar3, bar4)):
        notes += [(bar * meter + b, l, i) for b, l, i in content]
    return notes, motif


# ---------------------------------------------------------------------------
# Arrangement
# ---------------------------------------------------------------------------

class Score:
    """Collects notes and drum hits, in seconds, per mix bus."""

    def __init__(self):
        self.events = []  # (time, bus, kind, payload)

    def note(self, time, bus, instrument, midi, dur, vel, pan=0.0):
        self.events.append((time, bus, "note", (instrument, midi, dur, vel, pan)))

    def drum(self, time, letter, vel, pan=0.0):
        self.events.append((time, "drums", "drum", (letter, vel, pan)))


def arrange(name, theme):
    """Writes every section of a theme into a Score. Returns (score, length_seconds)."""
    rng = np.random.default_rng(theme["seed"])
    key = Key(theme["scale"], theme["tonic"])
    meter = theme["meter"]
    beat_s = 60.0 / theme["bpm"]
    bar_s = beat_s * meter
    score = Score()

    prog_a, prog_b = theme["progression_a"], theme["progression_b"]
    sections = [("intro", [prog_a[0]] * INTRO_BARS)]
    sections += [("A", prog_a), ("A2", prog_a), ("B", prog_b), ("A3", prog_a)]
    sections += [("outro", [0] * OUTRO_BARS)]

    # Melodies: A's two phrases are composed once and reused (A2 and A3
    # vary them), so the tune is recognisable; B gets its own.
    melodies = {}
    a1, motif_a = phrase_melody(theme, key, prog_a[:4], rng, final_tonic=False)
    a2, _ = phrase_melody(theme, key, prog_a[4:], rng, final_tonic=True, motif=motif_a)
    melodies["A"] = a1 + [(b + 4 * meter, l, i) for b, l, i in a2]
    b1, motif_b = phrase_melody(theme, key, prog_b[:4], rng, final_tonic=False)
    b2, _ = phrase_melody(theme, key, prog_b[4:], rng, final_tonic=False, motif=motif_b)
    melodies["B"] = b1 + [(b + 4 * meter, l, i) for b, l, i in b2]

    bar = 0
    voicing = None
    for section, chords in sections:
        for local_bar, chord in enumerate(chords):
            t0 = (bar + local_bar) * bar_s
            voicing = voice_chord(key, chord, voicing, theme["tonic"] + 3 + theme["acc_octave"],
                                  theme["tonic"] + 19 + theme["acc_octave"])
            write_accompaniment(score, theme, key, chord, voicing, t0, beat_s, section, rng)
            write_bass(score, theme, key, chord, t0, beat_s, section)
            if theme["pad"] and section != "intro" or theme["pad"] == "drone":
                pad_instrument = "drone" if theme["pad"] == "drone" else "pad"
                for midi in voicing:
                    score.note(t0, "pad", pad_instrument, midi, bar_s, 0.8, pan=rng.uniform(-0.4, 0.4))
            pattern = theme["drums"].get(section)
            if pattern:
                write_drums(score, pattern, t0, beat_s, rng)

        if section in ("A", "A2", "B", "A3"):
            source = melodies["B" if section == "B" else "A"]
            lead = theme["lead_b"] if section == "B" else theme["lead"]
            for beat, length, index in source:
                midi = key.midi(index)
                t = bar * bar_s + beat * beat_s
                vel = 0.85 if beat % meter == 0 else 0.7
                score.note(t, "lead", lead, midi, length * beat_s * 0.95, vel, pan=0.1)
                if section == "A2" and theme["double"]:
                    score.note(t, "lead", theme["double"], midi + 12, length * beat_s * 0.9, vel * 0.45, pan=-0.25)
        bar += len(chords)

    # Final tonic chord ringing over the outro's last bar.
    end = bar * bar_s
    final = voice_chord(key, 0, voicing, theme["tonic"] + 3, theme["tonic"] + 19)
    for midi in final:
        score.note(end, "acc", theme["acc_instrument"], midi, 2.5, 0.6)
    score.note(end, "lead", theme["lead"], key.midi(key.index_near(theme["melody_range"][0] + 7) // 7 * 7),
               2.5, 0.6)
    return score, end + 2.5 + TAIL_SECONDS


def write_accompaniment(score, theme, key, chord, voicing, t0, beat_s, section, rng):
    """Chord accompaniment of one bar, in the theme's style."""
    instrument = theme["acc_instrument"]
    style = theme["accompaniment"]
    meter = theme["meter"]
    if style == "arp":
        pattern = theme["acc_pattern"]
        steps = len(pattern)
        step_s = meter * beat_s / steps
        for k, which in enumerate(pattern):
            midi = voicing[which % len(voicing)] + (12 if which >= len(voicing) else 0)
            vel = 0.6 if k % (steps // meter) == 0 else 0.45
            score.note(t0 + k * step_s, "acc", instrument, midi, step_s * 1.6, vel,
                       pan=-0.3 + 0.6 * (k % 2))
    elif style in ("strum", "comp"):
        for beat in theme["acc_pattern"]:
            # Strum: strings sound a few milliseconds apart, low to high.
            for k, midi in enumerate(voicing):
                delay = 0.012 * k if style == "strum" else 0.0
                length = beat_s * (0.9 if style == "strum" else 0.4)
                score.note(t0 + beat * beat_s + delay, "acc", instrument, midi, length, 0.5,
                           pan=-0.35 + 0.35 * k)
    elif style == "sparse":
        # A chord tone now and then, high up: water drops in a cave.
        for beat in range(meter):
            if rng.random() < 0.35:
                midi = int(rng.choice(voicing)) + theme["acc_octave"] + 12
                score.note(t0 + (beat + rng.choice([0, 0.5])) * beat_s, "acc", instrument, midi,
                           beat_s, 0.35, pan=rng.uniform(-0.7, 0.7))


def write_bass(score, theme, key, chord, t0, beat_s, section):
    """Bass line of one bar from the theme's (beat, length, degree offset) pattern."""
    pattern = theme["bass_pattern"]
    if section == "intro" and theme["accompaniment"] != "sparse":
        pattern = pattern[:1]
    root_midi = key.midi(chord) - 12
    while root_midi > theme["tonic"] - 5:
        root_midi -= 12
    for beat, length, offset in pattern:
        midi = key.midi(chord + offset) - (key.midi(chord) - root_midi)
        score.note(t0 + beat * beat_s, "bass", theme["bass"], midi, length * beat_s * 0.92, 0.8)


def write_drums(score, pattern, t0, beat_s, rng):
    """One bar of drums from a sixteenth-note pattern string."""
    step_s = beat_s / 4
    for k, letter in enumerate(pattern):
        if letter == ".":
            continue
        vel = 0.9 if letter.isupper() or k % 4 == 0 else 0.6
        pan = {"s": 0.3, "r": -0.2, "t": 0.25, "l": -0.25}.get(letter.lower(), 0.0)
        score.drum(t0 + k * step_s, letter.lower(), vel, pan)


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

# Mix: bus gain and reverb send. Reverb is what turns dry synthesis into
# "outdoors"; caves get much more of it (REVERB_BY_THEME).
BUS_GAIN = {"lead": 1.0, "acc": 0.55, "bass": 0.7, "pad": 0.5, "drums": 0.55}
BUS_SEND = {"lead": 0.3, "acc": 0.35, "bass": 0.08, "pad": 0.4, "drums": 0.15}
# (reverb length in seconds, wet multiplier) per theme.
REVERB_BY_THEME = {"caves": (4.5, 2.2), "snow": (3.5, 1.5), "center": (1.4, 0.7)}
DEFAULT_REVERB = (2.2, 1.0)


def reverb_ir(seconds, rng):
    """Stereo impulse response: decaying, darkening noise with a pre-delay."""
    n = int(seconds * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    decay = np.exp(-t / (seconds / 6.9))  # -60 dB at `seconds`
    ir = np.stack([rng.standard_normal(n) * decay, rng.standard_normal(n) * decay])
    ir = np.stack([lowpass(ch, 5000) for ch in ir])
    predelay = int(0.02 * SAMPLE_RATE)
    ir = np.concatenate([np.zeros((2, predelay)), ir], axis=1)
    return ir / np.sqrt(np.sum(ir ** 2) / 2)


def render(name, theme):
    """Synthesises a theme to a stereo float array."""
    score, length = arrange(name, theme)
    rng = np.random.default_rng(theme["seed"] + 1)
    n_total = int(length * SAMPLE_RATE)
    buses = {bus: np.zeros((2, n_total)) for bus in BUS_GAIN}
    for time, bus, kind, payload in sorted(score.events, key=lambda e: e[0]):
        time = max(0.0, time + rng.uniform(-TIMING_JITTER, TIMING_JITTER))
        if kind == "note":
            instrument, midi, dur, vel, pan = payload
            vel *= 1 + rng.uniform(-VELOCITY_JITTER, VELOCITY_JITTER)
            sound = INSTRUMENTS[instrument](midi_to_hz(midi), dur, vel, rng)
        else:
            letter, vel, pan = payload
            vel *= 1 + rng.uniform(-VELOCITY_JITTER, VELOCITY_JITTER)
            sound = DRUMS[letter](vel, rng)
        start = int(time * SAMPLE_RATE)
        end = min(n_total, start + len(sound))
        if end <= start:
            continue
        # Constant-power pan.
        angle = (pan + 1) * np.pi / 4
        buses[bus][0, start:end] += sound[:end - start] * np.cos(angle)
        buses[bus][1, start:end] += sound[:end - start] * np.sin(angle)

    reverb_seconds, wet = REVERB_BY_THEME.get(name, DEFAULT_REVERB)
    ir = reverb_ir(reverb_seconds, np.random.default_rng(theme["seed"] + 2))
    dry = np.zeros((2, n_total))
    send = np.zeros((2, n_total))
    for bus, audio in buses.items():
        dry += audio * BUS_GAIN[bus]
        send += audio * BUS_GAIN[bus] * BUS_SEND[bus] * wet
    wet_l = signal.fftconvolve(send[0], ir[0])[:n_total]
    wet_r = signal.fftconvolve(send[1], ir[1])[:n_total]
    mix = dry + 0.35 * np.stack([wet_l, wet_r])
    mix = np.stack([highpass(ch, 30) for ch in mix])

    fade = int(FADE_IN_SECONDS * SAMPLE_RATE)
    mix[:, :fade] *= np.linspace(0, 1, fade)
    tail = int(TAIL_SECONDS * SAMPLE_RATE)
    mix[:, -tail:] *= np.linspace(1, 0, tail) ** 2
    return master(mix)


def master(mix):
    """Brings a mix to the target loudness under the peak ceiling (soft limiter)."""
    rms = np.sqrt(np.mean(mix ** 2))
    mix = mix * (10 ** (TARGET_RMS_DB / 20) / max(rms, 1e-9))
    # tanh limiter: transparent below the ceiling, rounds the rare peaks above.
    return PEAK_CEILING * np.tanh(mix / PEAK_CEILING)


def write_ogg(audio, path):
    """Encodes a stereo float array to Ogg Vorbis with ffmpeg, reproducibly."""
    pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2").T.copy()
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, "track.wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(SAMPLE_RATE)
            w.writeframes(pcm.tobytes())
        # bitexact: fixed Ogg serial numbers and no encoder tag, so the same
        # audio always gives the same file.
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
                        "-c:a", "libvorbis", "-q:a", str(VORBIS_QUALITY),
                        "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1",
                        path], check=True)


def main():
    names = sys.argv[1:] or list(THEMES)
    unknown = [n for n in names if n not in THEMES]
    if unknown:
        sys.exit(f"Unknown theme(s): {unknown}. Known: {list(THEMES)}")
    os.makedirs(OUT_DIR, exist_ok=True)
    for name in names:
        audio = render(name, THEMES[name])
        path = os.path.join(OUT_DIR, f"{name}.ogg")
        write_ogg(audio, path)
        print(f"{name}: {audio.shape[1] / SAMPLE_RATE:.0f} s, {os.path.getsize(path) / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
