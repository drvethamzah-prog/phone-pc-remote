"""Generate Android vectors from the pinned, unmodified Lucide icon geometry."""
from pathlib import Path
from urllib.request import urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://cdn.jsdelivr.net/npm/lucide-static@0.468.0/"
ICONS = ("keyboard", "touchpad", "monitor", "monitor-smartphone", "settings-2",
         "circle-stop", "circle-play", "music-2", "mouse", "delete", "corner-down-left",
         "volume-2", "volume-x", "volume-1", "play", "skip-back", "skip-forward",
         "chevron-down", "search", "copy", "clipboard-paste")
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def attr(name):
    return "{" + ANDROID + "}" + name


def geometry(node):
    kind = node.tag.split("}")[-1]
    a = node.attrib
    if kind == "path":
        return a["d"]
    if kind == "line":
        return f'M{a["x1"]},{a["y1"]} L{a["x2"]},{a["y2"]}'
    if kind in ("polyline", "polygon"):
        return "M" + a["points"] + (" Z" if kind == "polygon" else "")
    if kind == "circle":
        x, y, r = (float(a[k]) for k in ("cx", "cy", "r"))
        return f"M{x-r},{y} a{r},{r} 0,1,0 {2*r},0 a{r},{r} 0,1,0 {-2*r},0"
    if kind == "rect":
        x, y = float(a.get("x", 0)), float(a.get("y", 0))
        w, h, r = float(a["width"]), float(a["height"]), float(a.get("rx", 0))
        if not r:
            return f"M{x},{y} h{w} v{h} h{-w} Z"
        return (f"M{x+r},{y} h{w-2*r} a{r},{r} 0,0,1 {r},{r} v{h-2*r} "
                f"a{r},{r} 0,0,1 {-r},{r} h{-w+2*r} a{r},{r} 0,0,1 {-r},{-r} "
                f"v{-h+2*r} a{r},{r} 0,0,1 {r},{-r} Z")
    raise ValueError("Unsupported SVG element: " + kind)


out = ROOT / "app/src/main/res/drawable"
out.mkdir(parents=True, exist_ok=True)
for name in ICONS:
    with urlopen(BASE + "icons/" + name + ".svg", timeout=30) as response:
        svg = ET.fromstring(response.read())
    vector = ET.Element("vector", {attr("width"): "24dp", attr("height"): "24dp",
                                  attr("viewportWidth"): "24", attr("viewportHeight"): "24"})
    for node in svg:
        ET.SubElement(vector, "path", {attr("pathData"): geometry(node), attr("fillColor"): "@android:color/transparent",
                                      attr("strokeColor"): "#202728", attr("strokeWidth"): "1.8",
                                      attr("strokeLineCap"): "round", attr("strokeLineJoin"): "round"})
    ET.indent(vector)
    ET.ElementTree(vector).write(out / ("ic_" + name.replace("-", "_") + ".xml"), encoding="utf-8", xml_declaration=True)
with urlopen(BASE + "LICENSE", timeout=30) as response:
    (ROOT / "LUCIDE_LICENSE.txt").write_bytes(response.read())
print("Generated", len(ICONS), "Lucide Android vectors (0.468.0)")
