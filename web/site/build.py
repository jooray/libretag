#!/usr/bin/env python3
"""Builds index.html (EN) and sk/index.html (SK) from one template. Stdlib only:
   cd web/site && uv run python build.py
All copy lives in content.py (EN and SK side by side)."""
import os
import re
import pathlib, re
from content import T, SHARED

HERE = pathlib.Path(__file__).parent

def blaze(color, shape, cls=""):
    col = {"red": "#D7262E", "blue": "#1F5FB4", "green": "#2F8F3A", "yellow": "#F2C200"}[color]
    inner = {
        "stripe": f'<rect x="3" y="11" width="22" height="6" fill="{col}"/>',
        "diag": f'<path d="M4 20 L20 4 L25 9 L9 25 Z" fill="{col}" transform="translate(-1 0)"/>',
        "triangle": f'<path d="M14 5 L24 22 L4 22 Z" fill="{col}"/>',
        "circle": f'<circle cx="14" cy="14" r="7.5" fill="{col}"/>',
        "square": f'<rect x="7" y="7" width="14" height="14" fill="{col}"/>',
    }[shape]
    return (f'<svg class="blaze {cls}" viewBox="0 0 28 28" aria-hidden="true">'
            f'<rect class="edge" x="1" y="1" width="26" height="26" rx="4" fill="#fff" stroke="#1E1E1E" stroke-width="2"/>{inner}</svg>')

BAT = ('<svg viewBox="0 0 18 10" aria-hidden="true"><rect x=".75" y=".75" width="14.5" height="8.5" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.5"/>'
       '<rect x="16" y="3" width="2" height="4" fill="currentColor"/><rect x="2.5" y="2.5" width="{w}" height="5" fill="currentColor"/></svg>')

MAP = '''<svg viewBox="0 0 520 380" role="img" aria-labelledby="mapt mapd">
<title id="mapt">[[map_title]]</title><desc id="mapd">[[map_desc]]</desc>
<rect class="m-paper" width="520" height="380" fill="#F3F0E8"/>
<path class="m-forest" d="M0 250 C60 230 120 270 170 300 C200 320 150 360 120 380 L0 380Z" fill="#E0E7D0"/>
<path class="m-forest" d="M380 0 C420 30 470 20 520 50 L520 0Z" fill="#E0E7D0"/>
<path class="m-water" d="M330 380 C350 330 400 320 430 290 C470 250 500 250 520 230 L520 380Z" fill="#C3D7E8"/>
<g fill="none" stroke="#D7CBA9" stroke-width="1.2"><path d="M0 120 C100 90 180 140 260 100 C340 60 420 90 520 60"/><path d="M0 150 C100 120 180 170 260 130 C340 90 420 120 520 90"/><path d="M0 90 C100 60 180 110 260 70 C340 30 420 60 520 30" stroke-width="2"/></g>
<path class="m-road" d="M0 300 C90 280 160 230 240 220 C330 210 400 150 520 140" fill="none" stroke="#fff" stroke-width="7"/>
<path d="M0 300 C90 280 160 230 240 220 C330 210 400 150 520 140" fill="none" stroke="#D6CFBE" stroke-width="9" opacity=".35"/>
<circle cx="268" cy="190" r="150" fill="#1F5FB4" fill-opacity=".05" stroke="#1F5FB4" stroke-width="2" stroke-dasharray="3 7" stroke-linecap="round"/>
<circle cx="268" cy="190" r="92" fill="#1F5FB4" fill-opacity=".14" stroke="#1F5FB4" stroke-width="2.5" stroke-dasharray="9 6"/>
<g stroke="#1E1E1E" stroke-width="1.2" stroke-dasharray="2 4" opacity=".55">
<line x1="268" y1="190" x2="120" y2="110"/><line x1="268" y1="190" x2="390" y2="95"/><line x1="268" y1="190" x2="150" y2="290"/><line x1="268" y1="190" x2="420" y2="270"/></g>
<g class="m-rep" fill="#1E1E1E" stroke="#fff" stroke-width="2">
<rect x="113" y="103" width="14" height="14"/><rect x="383" y="88" width="14" height="14"/><rect x="143" y="283" width="14" height="14"/><rect x="413" y="263" width="14" height="14"/></g>
<g transform="translate(268 190)"><circle r="15" fill="#fff" stroke="#1E1E1E" stroke-width="2.5"/><rect x="-9" y="-3" width="18" height="6" fill="#D7262E"/></g>
<g class="m-lab" font-family="Archivo Narrow,Arial Narrow,sans-serif" font-weight="700" font-size="14" fill="#1E1E1E">
<text x="276" y="106">68 %</text><text x="348" y="140" transform="rotate(0)">95 %</text></g>
</svg>'''

def plate(color, shape, name, kind, st, pos, age, bat, ticket=None):
    w = {82: 11.5, 64: 9}.get(bat, 10)
    t = f'<span class="ticket">{ticket}</span>' if ticket else ""
    return (f'<article class="plate">{blaze(color, shape, "lg")}<div><div class="plate-name">{name}<span>{kind}</span></div>'
            f'<p class="st">{st}</p><p>{pos}</p></div><div class="plate-side"><span class="age">{age}</span>'
            f'<span class="bat">{BAT.format(w=w)}{bat}&nbsp;%</span></div>{t}</article>')

TEMPLATE = (HERE / "template.html").read_text()

def feature_items(items):
    marks = [("red", "stripe"), ("blue", "triangle"), ("green", "circle"), ("yellow", "square"), ("red", "diag")]
    out = []
    for i, (h, p) in enumerate(items):
        c, s = marks[i % len(marks)]
        out.append(f'<li>{blaze(c, s)}<div><h3>{h}</h3><p>{p}</p></div></li>')
    return "\n".join(out)

def build(lang):
    t = dict(T[lang]); t.update(SHARED)
    root = "" if lang == "en" else "../"
    t["root"] = root
    t["lang"] = lang
    t["map"] = MAP
    t["plate1"] = plate("red", "stripe", t["p1_name"], t["p1_kind"], t["p1_st"], "GPS ±8&nbsp;m · 48.70120, 21.25410", t["p1_age"], 82, t["p1_ticket"])
    t["plate2"] = plate("blue", "triangle", t["p2_name"], t["p2_kind"], t["p2_st"], t["p2_pos"], t["p2_age"], 64)
    t["features_html"] = feature_items(t["features"])
    t["next_plan_html"] = feature_items(t["next_plan"])
    for i, (c, s) in enumerate([("red", "stripe"), ("blue", "triangle"), ("green", "circle"), ("yellow", "square"), ("red", "diag")]):
        t[f"bz{i}"] = blaze(c, s)
    t["bz_h2_red"] = blaze("red", "stripe", "lg")
    t["bz_h2_blue"] = blaze("blue", "triangle", "lg")
    t["bz_h2_green"] = blaze("green", "circle", "lg")
    t["bz_h2_yellow"] = blaze("yellow", "square", "lg")
    t["bz_h2_diag"] = blaze("red", "diag", "lg")
    # two passes so values can contain other [[keys]]
    html = TEMPLATE
    for _ in range(3):
        html = re.sub(r"\[\[(\w+)\]\]", lambda m: str(t[m.group(1)]) if m.group(1) in t else m.group(0), html)
    left = re.findall(r"\[\[(\w+)\]\]", html)
    assert not left, left
    out = HERE / ("index.html" if lang == "en" else "sk/index.html")
    out.parent.mkdir(exist_ok=True)
    if os.environ.get("LT_VIDEO") == "0":  # LT_VIDEO=0 builds without the video section
        html = re.sub(r"<!--VIDEO-->.*?<!--/VIDEO-->", "", html, flags=re.S)
    if os.environ.get("LT_RELEASED") == "1":  # LT_RELEASED=1 turns on the download, source and bridge links
        repo = "https://github.com/jooray/libretag"
        links = {"apk": repo + "/releases/latest", "source": repo, "bridge-docs": repo + "#nostr--meshcore-bridge-nostrmesh"}
        for key, url in links.items():
            pat = r'href="#get" aria-disabled="true" data-placeholder="%s">(.*?)<span class="soon">[^<]*</span></a>' % re.escape(key)
            html, n = re.subn(pat, lambda m: 'href="%s" rel="noopener">%s</a>' % (url, m.group(1)), html)
            assert n == 1, key
        html = html.replace(t["get2_p"], t["get2_p_rel"])
    out.write_text(html)
    print("wrote", out)

for l in ("en", "sk"):
    build(l)
