"""Generates the weather icons as Android vector drawables plus an HTML sheet to eyeball them.
Same path data for both, so what the sheet shows is what the TV draws."""
import os, sys  # usage: python3 tools/<this>.py /tmp/sheet.html  (writes drawables + a preview sheet)

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
SHEET = sys.argv[1]

YELLOW, WHITE, GREY, BLUE, ICE, MOON = "#FFFFD60A", "#FFFFFFFF", "#FFC7C7CC", "#FF0A84FF", "#FF64D2FF", "#FFE5E5EA"

CLOUD = "M7,19 h10.5 a4,4 0 0 0 0.6,-7.96 A6,6 0 0 0 6.6,10.04 A4.5,4.5 0 0 0 7,19 z"
SUN_DISC = "M12,7.5 a4.5,4.5 0 1 1 0,9 a4.5,4.5 0 1 1 0,-9 z"
SUN_RAYS = ("M12,2.4v2.2 M12,19.4v2.2 M2.4,12h2.2 M19.4,12h2.2 "
            "M5.2,5.2l1.55,1.55 M17.25,17.25l1.55,1.55 M5.2,18.8l1.55,-1.55 M17.25,6.75l1.55,-1.55")
MOON_P = "M14.5,3.3 A8.7,8.7 0 1 0 20.7,16.4 A7.2,7.2 0 0 1 14.5,3.3 z"
RAIN = "M8.5,18.5 l-1.2,2.6 M12.5,18.5 l-1.2,2.6 M16.5,18.5 l-1.2,2.6"
DRIZZLE = "M9,18.8 l-0.6,1.3 M13,18.8 l-0.6,1.3 M17,18.8 l-0.6,1.3"
SNOW = ("M8.2,19.4 a1,1 0 1 1 0,2 a1,1 0 1 1 0,-2 z M12.2,20.4 a1,1 0 1 1 0,2 a1,1 0 1 1 0,-2 z "
        "M16.2,19.4 a1,1 0 1 1 0,2 a1,1 0 1 1 0,-2 z")
BOLT = "M12.8,15.5 L9.6,20 h2.6 l-1.2,3.5 l4.2,-5 h-2.6 l1.4,-3 z"
FOG = "M4,9h16 M6,13h12 M4,17h16"

# each layer: (path, fill, stroke, strokeWidth, (translateX, translateY, scale, pivotX, pivotY))
def L(path, fill=None, stroke=None, sw=0, t=(0, 0, 1, 12, 12)):
    return (path, fill, stroke, sw, t)

UP = (0, -3.2, 1, 12, 12)                         # cloud lifted to make room for rain/snow
SMALL_SUN = (-3.2, -3.4, 0.62, 12, 12)            # sun peeking from top-left
SMALL_MOON = (-3.0, -3.6, 0.62, 12, 12)
CLOUD_FRONT = (1.2, 1.4, 0.9, 12, 12)

ICONS = {
    "wx_clear_day":   [L(SUN_RAYS, stroke=YELLOW, sw=1.8), L(SUN_DISC, fill=YELLOW)],
    "wx_clear_night": [L(MOON_P, fill=MOON)],
    "wx_partly_day":  [L(SUN_RAYS, stroke=YELLOW, sw=2.4, t=SMALL_SUN), L(SUN_DISC, fill=YELLOW, t=SMALL_SUN),
                       L(CLOUD, fill=WHITE, t=CLOUD_FRONT)],
    "wx_partly_night": [L(MOON_P, fill=MOON, t=SMALL_MOON), L(CLOUD, fill=WHITE, t=CLOUD_FRONT)],
    "wx_cloudy":      [L(CLOUD, fill=GREY, t=(2.5, -2.6, 0.72, 12, 12)), L(CLOUD, fill=WHITE, t=(-0.6, 0.8, 1, 12, 12))],
    "wx_fog":         [L(FOG, stroke=GREY, sw=1.8)],
    "wx_drizzle":     [L(CLOUD, fill=WHITE, t=UP), L(DRIZZLE, stroke=ICE, sw=1.7)],
    "wx_rain":        [L(CLOUD, fill=WHITE, t=UP), L(RAIN, stroke=BLUE, sw=1.8)],
    "wx_snow":        [L(CLOUD, fill=WHITE, t=UP), L(SNOW, fill=ICE)],
    "wx_thunder":     [L(CLOUD, fill=WHITE, t=UP), L(BOLT, fill=YELLOW)],
}

def android(layers):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    for path, fill, stroke, sw, (tx, ty, s, px, py) in layers:
        p = [f'android:pathData="{path}"']
        if fill: p.append(f'android:fillColor="{fill}"')
        if stroke:
            p += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{sw}"',
                  'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        body = f'        <path {" ".join(p)} />'
        out += [f'    <group android:translateX="{tx}" android:translateY="{ty}" android:scaleX="{s}" '
                f'android:scaleY="{s}" android:pivotX="{px}" android:pivotY="{py}">', body, '    </group>']
    out.append('</vector>')
    return "\n".join(out) + "\n"

def svg(layers, size=96):
    def col(c): return "#" + c[3:] if c else "none"
    g = []
    for path, fill, stroke, sw, (tx, ty, s, px, py) in layers:
        # Android group order: scale around pivot, then translate
        tr = f"translate({tx},{ty}) translate({px},{py}) scale({s}) translate({-px},{-py})"
        g.append(f'<g transform="{tr}"><path d="{path}" fill="{col(fill)}" stroke="{col(stroke)}" '
                 f'stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round"/></g>')
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24">{"".join(g)}</svg>'

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    cells = []
    for name, layers in ICONS.items():
        open(os.path.join(OUT, name + ".xml"), "w").write(android(layers))
        cells.append(f'<div class="c">{svg(layers)}<div>{name}</div></div>')
    open(SHEET, "w").write(
        '<html><body style="background:#111;color:#aaa;font:12px Inter;display:flex;flex-wrap:wrap;gap:18px;padding:20px;width:760px">'
        '<style>.c{text-align:center;background:#1c1c22;padding:10px;border-radius:12px}</style>'
        + "".join(cells) + "</body></html>")
    # also export the svg snippets for the home-screen preview
    import json
    json.dump({n: svg(l, 30) for n, l in ICONS.items()}, open(SHEET + ".json", "w"))
    print("wrote", len(ICONS), "icons")
