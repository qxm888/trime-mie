#!/usr/bin/env python3
"""Generate the original short game sound effects (Python standard library only)."""
from pathlib import Path
import math
import struct
import wave

DESTINATION = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
RATE = 22050
DESTINATION.mkdir(parents=True, exist_ok=True)
for name, duration in [('typing_orb', .105), ('typing_heart', .16), ('typing_hit', .16)]:
    samples = []
    for i in range(int(RATE * duration)):
        t = i / RATE
        envelope = min(1, t / .006) * (1 - t / duration) ** 2
        if name == 'typing_orb':
            value = math.sin(2 * math.pi * (1000 * t - 2300 * t * t)) * .36
        elif name == 'typing_heart':
            value = (math.sin(2 * math.pi * 660 * t) + .5 * math.sin(2 * math.pi * 990 * t)) * .25
        else:
            value = (math.sin(2 * math.pi * 1320 * t) + .4 * math.sin(2 * math.pi * 1760 * t)) * math.exp(-t * 10) * .28
        samples.append(round(max(-1, min(1, value * envelope)) * 32767))
    with wave.open(str(DESTINATION / (name + '.wav')), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(struct.pack('<' + 'h' * len(samples), *samples))
