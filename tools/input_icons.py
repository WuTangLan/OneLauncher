"""White input icons (console, PC, set-top box) as vector drawables + an HTML sheet to check them."""
import os, sys  # usage: python3 tools/<this>.py /tmp/sheet.html  (writes drawables + a preview sheet)
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
SHEET = sys.argv[1]

ICONS = {
    # controller body with the d-pad and two buttons cut out (even-odd fill)
    "ic_gamepad": "M7,7h10c2.8,0 4.6,2.3 5,5.2l0.8,5c0.3,2 -1.9,3.2 -3.4,1.9L16.5,16.5h-9L4.6,19.1"
                  "C3.1,20.4 0.9,19.2 1.2,17.2l0.8,-5C2.4,9.3 4.2,7 7,7z "
                  "M6.4,9.6h1.6v1.6h1.6v1.6H8v1.6H6.4v-1.6H4.8v-1.6h1.6z "
                  "M15,10.8a1,1 0 1 0 2,0a1,1 0 1 0 -2,0z M17.4,13.2a1,1 0 1 0 2,0a1,1 0 1 0 -2,0z",
    # monitor outline with a stand
    "ic_pc": "M3,3.5h18a1.5,1.5 0 0 1 1.5,1.5v10.5a1.5,1.5 0 0 1 -1.5,1.5H3a1.5,1.5 0 0 1 -1.5,-1.5V5"
             "a1.5,1.5 0 0 1 1.5,-1.5z M3.4,5.4v9.7h17.2V5.4z "
             "M10.6,17h2.8v2.4h-2.8z M7.5,19.2h9a0.8,0.8 0 0 1 0,1.6h-9a0.8,0.8 0 0 1 0,-1.6z",
    # flat receiver box with a light and a display slot, on two feet
    "ic_settopbox": "M2.5,9h19a1.5,1.5 0 0 1 1.5,1.5v5a1.5,1.5 0 0 1 -1.5,1.5h-19a1.5,1.5 0 0 1 -1.5,-1.5v-5"
                    "a1.5,1.5 0 0 1 1.5,-1.5z "
                    "M4.2,13a1,1 0 1 0 2,0a1,1 0 1 0 -2,0z M13,12.2h6.4v1.6H13z "
                    "M4,17h2.5v1.6H4z M17.5,17h2.5v1.6h-2.5z",
}

for name, path in ICONS.items():
    open(os.path.join(OUT, name + ".xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="48dp" android:height="48dp" android:viewportWidth="24" android:viewportHeight="24">\n'
        f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd"\n        android:pathData="{path}" />\n'
        '</vector>\n')
cells = "".join(
    f'<div class="c"><svg width="96" height="96" viewBox="0 0 24 24"><path fill="#fff" fill-rule="evenodd" d="{p}"/></svg>'
    f'<div>{n}</div></div>' for n, p in ICONS.items())
open(SHEET, "w").write('<html><body style="background:#1E2A4A;color:#ccd;font:12px Inter;display:flex;gap:18px;padding:20px">'
                       '<style>.c{text-align:center}</style>' + cells + '</body></html>')
import json; json.dump(ICONS, open(SHEET + ".json", "w"))
print("ok")
