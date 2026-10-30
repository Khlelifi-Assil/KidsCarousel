#!/usr/bin/env python3
"""Génère pack/items.json + pack/images/*.jpg à partir de tools/words.csv.
- Images: Openverse (licences libres, mature=false) -> redimensionnées 512px
- Traductions fr/ar vides dans le CSV: MyMemory (à relire, surtout l'arabe)
Relancer le script ne retélécharge que ce qui manque."""
import csv, io, json, os, re, time, urllib.parse, urllib.request
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "pack"); IMG = os.path.join(OUT, "images")
JSON_PATH = os.path.join(OUT, "items.json")
COLORS = {"fruits": "#FFE0E0", "animaux": "#FFF2CC", "vehicules": "#DDEBFF",
          "objets": "#E2F5E2", "nature": "#D8F3F7"}
UA = {"User-Agent": "KidsCarouselPack/1.0 (educational)"}

def http(url):
    for n in range(3):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()
        except Exception:
            if n == 2: raise
            time.sleep(3)

def translate(text, lang):
    q = urllib.parse.urlencode({"q": text, "langpair": f"en|{lang}"})
    t = json.loads(http("https://api.mymemory.translated.net/get?" + q))["responseData"]["translatedText"].strip()
    return t.lower() if lang == "fr" else t

def find_image(query):
    q = urllib.parse.urlencode({"q": query, "license_type": "commercial", "mature": "false",
                                "extension": "jpg", "page_size": 10})
    for r in json.loads(http("https://api.openverse.org/v1/images/?" + q)).get("results", []):
        if (r.get("width") or 0) >= 400 and (r.get("height") or 0) >= 400:
            credit = f'{r.get("title") or "?"} - {r.get("creator") or "?"} - ' \
                     f'{(r.get("license") or "").upper()} {r.get("license_version") or ""} - {r.get("foreign_landing_url")}'
            return r["url"], credit
    return None, None

def main():
    os.makedirs(IMG, exist_ok=True)
    old = {}
    if os.path.exists(JSON_PATH):
        old = {i["id"]: i for i in json.load(open(JSON_PATH, encoding="utf-8"))["items"]}
    items = []
    for row in csv.DictReader(open(os.path.join(ROOT, "tools", "words.csv"), encoding="utf-8")):
        en = row["en"].strip()
        if not en: continue
        pid = re.sub(r"[^a-z0-9]+", "_", en.lower()).strip("_")
        prev = old.get(pid, {})
        try:
            fr = row["fr"].strip() or prev.get("fr") or translate(en, "fr")
            ar = row["ar"].strip() or prev.get("ar") or translate(en, "ar")
            rel = f"images/{pid}.jpg"; dest = os.path.join(OUT, rel); credit = prev.get("credit", "")
            if not os.path.exists(dest):
                url, credit = find_image(row.get("query") or en)
                if not url: print("PAS D'IMAGE:", en); continue
                im = Image.open(io.BytesIO(http(url))).convert("RGB")
                im.thumbnail((512, 512)); im.save(dest, "JPEG", quality=85)
                time.sleep(1)
            items.append({"id": pid, "img": rel, "ar": ar, "fr": fr, "en": en,
                          "color": COLORS.get(row.get("category", ""), "#EEEEEE"), "credit": credit})
            print("OK", en, fr, ar)
        except Exception as e:
            print("ERREUR", en, e)
    json.dump({"version": int(time.time()), "items": items}, open(JSON_PATH, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "CREDITS.md"), "w", encoding="utf-8") as f:
        f.write("# Crédits images (Openverse)\n\n" + "\n".join(f"- {i['en']}: {i['credit']}" for i in items) + "\n")
    print(len(items), "éléments")

if __name__ == "__main__":
    main()
