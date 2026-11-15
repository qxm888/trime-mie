# SPDX-License-Identifier: GPL-3.0-or-later
"""Original, static soft colour fields. Authoring only; no runtime animation or dependencies."""
import io
import subprocess
from PIL import Image

BACKGROUND = '内置材质/精雕一辑/液态玻璃/背景.png'

def backdrop():
    # Soft original colour fields, intentionally without drawn keycaps or lettering.
    # A quiet neutral frost retains background colour without fighting live labels.
    svg = '''<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="860" viewBox="0 0 1200 860">
    <defs>
      <radialGradient id="pink"><stop stop-color="#E2B8B0" stop-opacity=".85"/><stop offset="1" stop-color="#E2B8B0" stop-opacity="0"/></radialGradient>
      <radialGradient id="blue"><stop stop-color="#A8CCD8" stop-opacity=".9"/><stop offset="1" stop-color="#A8CCD8" stop-opacity="0"/></radialGradient>
      <radialGradient id="green"><stop stop-color="#CCD991" stop-opacity=".8"/><stop offset="1" stop-color="#CCD991" stop-opacity="0"/></radialGradient>
      <radialGradient id="cream"><stop stop-color="#F0DDC4" stop-opacity=".85"/><stop offset="1" stop-color="#F0DDC4" stop-opacity="0"/></radialGradient>
      <linearGradient id="base" x2=".7" y2="1"><stop stop-color="#ABA99F"/><stop offset="1" stop-color="#9FACA3"/></linearGradient>
    </defs>
    <path fill="url(#base)" d="M0 0H1200V860H0Z"/>
    <ellipse cx="180" cy="30" rx="650" ry="530" fill="url(#pink)"/>
    <ellipse cx="100" cy="510" rx="650" ry="480" fill="url(#blue)"/>
    <ellipse cx="1030" cy="520" rx="590" ry="650" fill="url(#green)"/>
    <ellipse cx="600" cy="900" rx="650" ry="520" fill="url(#cream)"/>
    </svg>'''
    return Image.open(io.BytesIO(subprocess.check_output(['rsvg-convert'], input=svg.encode()))).convert('RGB')
