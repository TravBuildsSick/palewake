import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.misc.transform import Transform

FONTS = "/mnt/skills/examples/canvas-design/canvas-fonts"
OUT = "/mnt/user-data/outputs"
os.makedirs(OUT, exist_ok=True)

# Per-brand palettes. Wrackline keeps the sunset ramp with dark ink; Palewake
# inverts it — deep blue-green ground, pale ghostly ink with a spectral echo.
PALETTES = {
    "wrackline": {
        "stops": [
            (0.00, "#0B1B3A"),
            (0.10, "#7C2622"),
            (0.38, "#CE4E1C"),
            (0.70, "#ED8B1F"),
            (1.00, "#F4C63D"),
        ],
        "ink": "#093C39",
        "ghost": None,
    },
    "palewake": {
        "stops": [
            (0.00, "#02100F"),
            (0.34, "#062B28"),
            (0.70, "#0B4A42"),
            (1.00, "#12665A"),
        ],
        "ink": "#DEEFE7",
        "ghost": ("#7FE3CB", 0.22),
    },
}

INK_TOKEN = "@INK@"


def paint(body, pal, canvas_h):
    """Render a token-marked body: a soft offset echo underneath, solid on top."""
    solid = body.replace(INK_TOKEN, pal["ink"])
    if not pal["ghost"]:
        return solid
    colour, alpha = pal["ghost"]
    off = canvas_h * 0.008
    echo = body.replace(INK_TOKEN, colour)
    return (
        f'  <g opacity="{alpha}" transform="translate(0 {-off:.2f})">\n{echo}\n  </g>\n'
        f"{solid}"
    )


def text_outline(font_file, text, cap_height, tracking=0.0, target_width=None):
    """Outline `text` at a given optical cap height. tracking is a fraction of
    cap_height inserted between glyphs; target_width solves tracking to fit."""
    if target_width is not None:
        _, natural, _ = text_outline(font_file, text, cap_height, 0.0)
        tracking = (target_width - natural) / (len(text) - 1) / cap_height

    font = TTFont(os.path.join(FONTS, font_file))
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()

    rec = RecordingPen()
    gs[cmap[ord("H")]].draw(rec)
    ys = [pt[1] for _, args in rec.value for pt in args]
    scale = cap_height / (max(ys) - min(ys))
    track_units = (tracking * cap_height) / scale

    pen = SVGPathPen(gs)
    x = 0.0
    for ch in text:
        gname = cmap[ord(ch)]
        gs[gname].draw(TransformPen(pen, Transform(scale, 0, 0, -scale, x * scale, 0)))
        x += gs[gname].width + track_units
    return pen.getCommands(), (x - track_units) * scale, cap_height


def strand(x0, x1, y, amp, teeth, tick_len):
    """Ragged tide-debris line: zigzag polyline plus debris hanging from the lows."""
    pts, step = [], (x1 - x0) / teeth
    for i in range(teeth + 1):
        px = x0 + i * step
        py = y if i in (0, teeth) else (y - amp if i % 2 else y + amp * 0.85)
        pts.append((px, py))
    d = "M" + " L".join(f"{px:.2f} {py:.2f}" for px, py in pts)

    ticks = []
    for i in range(1, teeth):
        px, py = pts[i]
        if py <= y:
            continue
        dx = tick_len * 0.16 * (1 if i % 4 == 1 else -1)
        ticks.append(f"M{px:.2f} {py:.2f} L{px + dx:.2f} {py + tick_len:.2f}")
    return d, " ".join(ticks)


def divider(x0, x1, y, jagged):
    """Word-splitting rule. Wrackline gets the jagged debris strand; Palewake
    gets a smooth wake swell with trailing ripples."""
    if jagged:
        return strand(x0, x1, y, amp=5.5, teeth=13, tick_len=11)

    span, waves = x1 - x0, 5
    step = span / waves
    d = f"M{x0:.2f} {y:.2f}"
    for i in range(waves):
        a, b = x0 + i * step, x0 + (i + 1) * step
        amp = -7.0 if i % 2 == 0 else 7.0
        d += f" C{a + step * 0.34:.2f} {y + amp:.2f} {b - step * 0.34:.2f} {y + amp:.2f} {b:.2f} {y:.2f}"

    # trailing ripples would foul the cap line of the lower word; swell only
    return d, ""


def mark_strand(S):
    """Wrackline: jagged tide-debris line with kelp hanging from the lows."""
    d, t = strand(74, S - 74, S / 2 - 44, amp=44, teeth=7, tick_len=112)
    body = (
        f'  <g stroke="{INK_TOKEN}" fill="none" stroke-width="36" stroke-linecap="round" '
        f'stroke-linejoin="round"><path d="{d}"/></g>\n'
        f'  <g stroke="{INK_TOKEN}" fill="none" stroke-width="14" stroke-linecap="round">'
        f'<path d="{t}"/></g>'
    )
    return body, (56, 150, S - 56, 380)


def skull(cx, top, w=46.0):
    """Minimal skull silhouette. Sockets and nose are cut with evenodd so the
    background gradient shows through instead of being painted over."""
    k = w / 46.0
    def X(v):
        return cx + v * k
    def Y(v):
        return top + v * k

    cranium = (
        f"M{X(-46):.1f} {Y(58):.1f} "
        f"C{X(-46):.1f} {Y(16):.1f} {X(-25):.1f} {Y(0):.1f} {X(0):.1f} {Y(0):.1f} "
        f"C{X(25):.1f} {Y(0):.1f} {X(46):.1f} {Y(16):.1f} {X(46):.1f} {Y(58):.1f} "
        f"C{X(46):.1f} {Y(70):.1f} {X(38):.1f} {Y(74):.1f} {X(30):.1f} {Y(76):.1f} "
        f"L{X(30):.1f} {Y(90):.1f} "
        f"C{X(30):.1f} {Y(98):.1f} {X(16):.1f} {Y(100):.1f} {X(0):.1f} {Y(100):.1f} "
        f"C{X(-16):.1f} {Y(100):.1f} {X(-30):.1f} {Y(98):.1f} {X(-30):.1f} {Y(90):.1f} "
        f"L{X(-30):.1f} {Y(76):.1f} "
        f"C{X(-38):.1f} {Y(74):.1f} {X(-46):.1f} {Y(70):.1f} {X(-46):.1f} {Y(58):.1f} Z"
    )
    socket = lambda ox: (
        f"M{X(ox - 17):.1f} {Y(45):.1f} "
        f"a{17 * k:.1f} {18 * k:.1f} 0 1 0 {34 * k:.1f} 0 "
        f"a{17 * k:.1f} {18 * k:.1f} 0 1 0 {-34 * k:.1f} 0 Z"
    )
    nose = f"M{X(0):.1f} {Y(66):.1f} L{X(-10):.1f} {Y(84):.1f} L{X(10):.1f} {Y(84):.1f} Z"

    d = " ".join([cranium, socket(-21), socket(21), nose])
    return d, (cx - w, top, cx + w, top + 100 * k)


def mark_wake(S):
    """Palewake: a skull trailing nested wake chevrons."""
    cx = S / 2
    sk_d, sk_box = skull(cx, 56.0, w=62.0)

    rows = ((216.0, 76.0, 74.0, 32.0), (284.0, 118.0, 78.0, 25.0), (352.0, 160.0, 82.0, 20.0))
    chevrons = "\n".join(
        f'  <path d="M{cx - half:.1f} {y + drop:.1f} L{cx:.1f} {y:.1f} '
        f'L{cx + half:.1f} {y + drop:.1f}" stroke="{INK_TOKEN}" fill="none" '
        f'stroke-width="{sw:g}" stroke-linecap="round" stroke-linejoin="round"/>'
        for y, half, drop, sw in rows
    )

    body = f'  <path d="{sk_d}" fill="{INK_TOKEN}" fill-rule="evenodd"/>\n{chevrons}'
    widest = rows[-1]
    return body, (cx - widest[1] - 11, sk_box[1], cx + widest[1] + 11, widest[0] + widest[2] + 11)


def gradient_def(gid, stops):
    stops = "\n".join(
        f'      <stop offset="{o:.0%}" stop-color="{c}"/>' for o, c in stops
    )
    return (
        f'  <defs>\n    <linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">\n'
        f"{stops}\n    </linearGradient>\n  </defs>"
    )


def svg(w, h, body, title):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" '
        f'viewBox="0 0 {w:g} {h:g}" role="img" aria-label="{title}">\n{body}\n</svg>\n'
    )


def build(slug, top_word, bot_word, title, mark, jagged):
    pal = PALETTES[slug]
    # ---------------------------------------------------------- lockup tile
    CAP, PAD, GAP, PAD_TOP = 76.0, 42.0, 40.0, 62.0
    top_d, top_w, _ = text_outline("BigShoulders-Bold.ttf", top_word, CAP, 0.09)
    bot_d, bot_w, _ = text_outline(
        "BigShoulders-Regular.ttf", bot_word, CAP, target_width=top_w
    )
    inner = max(top_w, bot_w)
    W, H = inner + PAD * 2, PAD_TOP + PAD + CAP * 2 + GAP
    sy = PAD_TOP + CAP + GAP / 2
    sd, st = divider(PAD, W - PAD, sy, jagged)
    gid = f"{slug}-sky"

    content = f"""  <g fill="{INK_TOKEN}">
    <path transform="translate({PAD + (inner - top_w) / 2:.2f} {PAD_TOP + CAP:.2f})" d="{top_d}"/>
    <path transform="translate({PAD + (inner - bot_w) / 2:.2f} {PAD_TOP + CAP * 2 + GAP:.2f})" d="{bot_d}"/>
  </g>
  <g stroke="{INK_TOKEN}" fill="none" stroke-width="5" stroke-linecap="round" stroke-linejoin="round">
    <path d="{sd}"/>
  </g>
  <g stroke="{INK_TOKEN}" fill="none" stroke-width="3" stroke-linecap="round">
    <path d="{st}"/>
  </g>"""
    body = (
        f'{gradient_def(gid, pal["stops"])}\n'
        f'  <rect width="{W:g}" height="{H:g}" rx="26" fill="url(#{gid})"/>\n'
        f"{paint(content, pal, H)}"
    )
    open(f"{OUT}/{slug}-lockup.svg", "w").write(svg(W, H, body, title))

    # ------------------------------------------------------------ icon tile
    S = 512.0
    mbody, bbox = mark(S)
    gid2 = f"{slug}-sky-icon"
    micon = (
        f'{gradient_def(gid2, pal["stops"])}\n'
        f'  <rect width="{S:g}" height="{S:g}" rx="112" fill="url(#{gid2})"/>\n'
        f"{paint(mbody, pal, S)}"
    )
    open(f"{OUT}/{slug}-icon.svg", "w").write(svg(S, S, micon, f"{title} icon"))

    # ---------------------------------- adaptive icon: fg + bg as two layers
    # Android masks the outer ring; the mark must fit the central 66dp safe zone.
    A, SAFE = 108.0, 66.0
    x0, y0, x1, y1 = bbox
    k = SAFE / max(x1 - x0, y1 - y0)
    tx = A / 2 - ((x0 + x1) / 2) * k
    ty = A / 2 - ((y0 + y1) / 2) * k
    fg = (
        f'  <g transform="translate({tx:.2f} {ty:.2f}) scale({k:.4f})">\n'
        f"{paint(mbody, pal, A / k)}\n  </g>"
    )
    open(f"{OUT}/{slug}-adaptive-foreground.svg", "w").write(
        svg(A, A, fg, f"{title} adaptive foreground")
    )
    gid3 = f"{slug}-sky-bg"
    bg = f"""{gradient_def(gid3, pal["stops"])}
  <rect width="{A:g}" height="{A:g}" fill="url(#{gid3})"/>"""
    open(f"{OUT}/{slug}-adaptive-background.svg", "w").write(
        svg(A, A, bg, f"{title} adaptive background")
    )


build("palewake", "PALE", "WAKE", "Palewake", mark_wake, jagged=False)
build("wrackline", "WRACK", "LINE", "Wrackline", mark_strand, jagged=True)
print("done")
