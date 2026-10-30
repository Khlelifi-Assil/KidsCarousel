#!/usr/bin/env python3
"""Moteur de contenu: catégories + images PNG réelles à fond transparent.

  tools/categories.json  -> catégories (id, emoji, noms ar/fr/en, couleur, suffixe de recherche)
  tools/words.csv        -> mots: en,fr,ar,category,query   (fr/ar vides = traduits automatiquement)
  pack/items.json        -> résultat (catégories + éléments), pack/images/<catégorie>/<mot>.png

Pour chaque mot, les sources sont essayées dans l'ordre:
  1. Pixabay (clé PIXABAY_KEY): d'abord images déjà transparentes (colors=transparent), puis photos
  2. Openverse (PNG), 3. Wikipedia, 4. Openverse (tout format)
Chaque image est téléchargée et analysée:
  - vraie transparence (canal alpha)  -> gardée telle quelle
  - sinon, détourage automatique avec rembg (si installé) -> PNG transparent
  - sinon ignorée (ou gardée en JPG opaque avec --allow-opaque)
Le PNG final est recadré sur l'objet et réduit à 512 px.

Options: --refresh (retraduire) --refresh-images (retélécharger) --category ID --limit N
         --allow-opaque --no-rembg
Code de sortie 1 si rien n'a pu être généré."""
import argparse, csv, io, itertools, json, os, re, sys, time
import urllib.error, urllib.parse, urllib.request
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "pack")
JSON_PATH = os.path.join(OUT, "items.json")
REPO = os.environ.get("GITHUB_REPOSITORY", "local")
UA = {"User-Agent": f"KidsCarouselPack/2.0 (https://github.com/{REPO}; educational use)"}
PIXABAY_KEY = os.environ.get("PIXABAY_KEY", "").strip()
MM_EMAIL = os.environ.get("MYMEMORY_EMAIL", "").strip()
AR = re.compile("[\u0600-\u06FF]")


class Quota(Exception):
    pass


def http(url, tries=3):
    last = None
    for n in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40).read()
        except urllib.error.HTTPError as e:
            if e.code == 429: raise Quota(url.split("?")[0])
            if e.code in (400, 401, 403, 404): raise
            last = e
        except Exception as e:
            last = e
        time.sleep(2 * (n + 1))
    raise last


# ------------------------------------------------------------ traduction
def tr_google(text, to):
    q = urllib.parse.urlencode({"client": "gtx", "sl": "en", "tl": to, "dt": "t", "q": text})
    d = json.loads(http("https://translate.googleapis.com/translate_a/single?" + q))
    return "".join(p[0] for p in d[0] if p and p[0]).strip()


def tr_mymemory(text, to):
    params = {"q": text, "langpair": f"en|{to}"}
    if MM_EMAIL: params["de"] = MM_EMAIL
    d = json.loads(http("https://api.mymemory.translated.net/get?" + urllib.parse.urlencode(params)))
    if str(d.get("responseStatus")) != "200": raise RuntimeError(f"MyMemory {d.get('responseStatus')}")
    t = d["responseData"]["translatedText"].strip()
    if "MYMEMORY" in t.upper(): raise RuntimeError("quota MyMemory")
    return t


def valid(t, lang):
    if not t or "MYMEMORY" in t.upper(): return False
    return bool(AR.search(t)) if lang == "ar" else not AR.search(t)


def translate(text, to):
    errs = []
    for fn in (tr_google, tr_mymemory):
        try:
            t = fn(text, to); t = t if to == "ar" else t.lower()
            if valid(t, to): return t
            errs.append(f"{fn.__name__}: inutilisable ({t!r})")
        except Exception as e:
            errs.append(f"{fn.__name__}: {e}")
    raise RuntimeError("traduction impossible - " + " | ".join(errs))


# ------------------------------------------------------------ sources d'images
def c_pixabay(q, suffix):
    if not PIXABAY_KEY: return
    base = {"key": PIXABAY_KEY, "safesearch": "true", "per_page": 8, "min_width": 400}
    for extra in ({"q": q, "image_type": "all", "colors": "transparent"},
                  {"q": f"{q} {suffix}".strip(), "image_type": "photo"}):
        for h in json.loads(http("https://pixabay.com/api/?" + urllib.parse.urlencode({**base, **extra}))).get("hits", []):
            url = h.get("webformatURL") or h.get("largeImageURL")
            if url: yield url, f"Pixabay - {h.get('user')} - {h.get('pageURL')} (Pixabay Content License)"


def c_openverse(q, suffix, png=False):
    p = {"q": q, "license_type": "commercial", "mature": "false", "page_size": 8}
    if png: p["extension"] = "png"
    for r in json.loads(http("https://api.openverse.org/v1/images/?" + urllib.parse.urlencode(p))).get("results", []):
        if (r.get("width") or 0) >= 400:
            yield r["url"], (f'{r.get("title")} - {r.get("creator")} - {(r.get("license") or "").upper()} '
                             f'{r.get("license_version") or ""} - {r.get("foreign_landing_url")}')


def c_wikipedia(q, suffix):
    p = urllib.parse.urlencode({"action": "query", "generator": "search", "gsrsearch": q, "gsrnamespace": 0,
                                "gsrlimit": 8, "prop": "pageimages", "piprop": "thumbnail",
                                "pithumbsize": 640, "format": "json"})
    pages = json.loads(http("https://en.wikipedia.org/w/api.php?" + p)).get("query", {}).get("pages", {})
    for pg in sorted(pages.values(), key=lambda x: x.get("index", 99)):
        th = pg.get("thumbnail", {}).get("source")
        if th:
            t = pg["title"]
            yield th, f"Wikipedia - {t} - https://en.wikipedia.org/wiki/{urllib.parse.quote(t.replace(' ', '_'))}"


SOURCES = ([("pixabay", c_pixabay)] if PIXABAY_KEY else []) + [
    ("openverse-png", lambda q, s: c_openverse(q, s, True)), ("wikipedia", c_wikipedia), ("openverse", c_openverse)]
disabled = set()


def candidates(query, suffix):
    for name, fn in SOURCES:
        if name in disabled: continue
        try:
            for url, credit in fn(query, suffix):
                if url: yield url, credit
        except Quota as e:
            print(f"  ! limite atteinte pour {name} ({e}), source désactivée"); disabled.add(name)
        except Exception as e:
            print(f"  ! {name}: {type(e).__name__}: {e}")


# ------------------------------------------------------------ analyse / détourage
_rembg = None


def remove_bg(im):
    """Détourage automatique (rembg). Renvoie None si rembg n'est pas installé."""
    global _rembg
    if _rembg is None:
        try:
            from rembg import remove, new_session
            _rembg = (remove, new_session(os.environ.get("REMBG_MODEL", "u2net")))
        except Exception as e:
            print("  ! rembg indisponible:", e); _rembg = False
    if not _rembg: return None
    return _rembg[0](im, session=_rembg[1])


def cutout(im):
    """(est_un_vrai_détourage, image RGBA). Vrai si 8% à 92% des pixels sont transparents."""
    if im.mode == "P" and "transparency" in im.info: im = im.convert("RGBA")
    if im.mode not in ("RGBA", "LA"): return False, im
    im = im.convert("RGBA")
    h = im.getchannel("A").histogram()
    t = sum(h[:16]) / (im.width * im.height)
    return 0.08 <= t <= 0.92, im


def save_png(im, dest):
    bbox = im.getchannel("A").point(lambda v: 255 if v > 16 else 0).getbbox()
    if not bbox or min(bbox[2] - bbox[0], bbox[3] - bbox[1]) < 120: raise ValueError("objet trop petit/vide")
    l, t, r, b = bbox; m = int(0.04 * max(r - l, b - t))
    im = im.crop((max(0, l - m), max(0, t - m), min(im.width, r + m), min(im.height, b + m)))
    im.thumbnail((512, 512))
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    im.save(dest, "PNG", optimize=True)


def fetch_image(query, suffix, dest_png, allow_opaque, use_rembg):
    """Renvoie (chemin_écrit, crédit) ou None."""
    opaque = None
    for url, credit in itertools.islice(candidates(query, suffix), 14):
        try:
            im = Image.open(io.BytesIO(http(url))); im.load()
            if min(im.size) < 250: raise ValueError("image trop petite")
            ok, rgba = cutout(im)
            if not ok and use_rembg:
                out = remove_bg(im.convert("RGB"))
                if out is not None: ok, rgba = cutout(out)
            if ok:
                save_png(rgba, dest_png); return dest_png, credit
            if allow_opaque and opaque is None: opaque = (im.convert("RGB"), credit)
            print(f"  - sans transparence exploitable: {url[:70]}")
        except Exception as e:
            print(f"  - image refusée ({type(e).__name__}: {e}) {url[:70]}")
    if opaque:
        jpg = dest_png[:-4] + ".jpg"; os.makedirs(os.path.dirname(jpg), exist_ok=True)
        opaque[0].thumbnail((512, 512)); opaque[0].save(jpg, "JPEG", quality=85)
        return jpg, opaque[1]
    return None


# ------------------------------------------------------------ fichiers d'entrée
def read_categories():
    p = os.path.join(ROOT, "tools", "categories.json")
    return {c["id"]: c for c in json.load(open(p, encoding="utf-8"))} if os.path.exists(p) else {}


def read_words():
    text = open(os.path.join(ROOT, "tools", "words.csv"), encoding="utf-8-sig").read()
    lines = [l for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
    if not lines: return []
    delim = max(",;\t", key=lines[0].count)
    rows = list(csv.reader(lines, delimiter=delim))
    header = [h.strip().lower() for h in rows[0]]
    if "en" in header: data = rows[1:]
    else: header, data = ["en", "fr", "ar", "category", "query"], rows
    return [{header[i]: r[i].strip() for i in range(min(len(header), len(r)))} for r in data]


# ------------------------------------------------------------ main
def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true"); ap.add_argument("--refresh-images", action="store_true")
    ap.add_argument("--category"); ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--allow-opaque", action="store_true"); ap.add_argument("--no-rembg", action="store_true")
    a, _ = ap.parse_known_args(sys.argv[1:] if argv is None else argv)

    cats = read_categories()
    old = {}
    if os.path.exists(JSON_PATH):
        try: old = {i["id"]: i for i in json.load(open(JSON_PATH, encoding="utf-8"))["items"]}
        except Exception: old = {}
    items, failed, done = [], [], 0
    print("Sources:", [n for n, _ in SOURCES], "| rembg:", not a.no_rembg)
    for row in read_words():
        en = row.get("en", "").strip(); cid = row.get("category", "").strip() or "mine"
        if not en or (a.category and cid != a.category): continue
        if a.limit and done >= a.limit: break
        done += 1
        pid = re.sub(r"[^a-z0-9]+", "_", en.lower()).strip("_")
        prev = old.get(pid, {}) if not a.refresh else {}
        c = cats.get(cid, {})
        try:
            def pick(key, lang):
                for v in (row.get(key, ""), prev.get(key, "")):
                    if v and valid(v, lang): return v
                return translate(en, lang)
            fr, ar = pick("fr", "fr"), pick("ar", "ar")
            png = os.path.join(OUT, "images", cid, pid + ".png"); jpg = png[:-4] + ".jpg"
            credit, found = prev.get("credit", ""), None
            if not a.refresh_images:
                found = png if os.path.exists(png) else jpg if os.path.exists(jpg) else None
            if not found:
                res = fetch_image(row.get("query") or en, c.get("suffix", "isolated white background"),
                                  png, a.allow_opaque, not a.no_rembg)
                if not res: raise RuntimeError("aucune image transparente exploitable")
                found, credit = res; time.sleep(1)
            rel = os.path.relpath(found, OUT).replace(os.sep, "/")
            items.append({"id": pid, "img": rel, "ar": ar, "fr": fr, "en": en, "category": cid,
                          "color": c.get("color", "#EEEEEE"), "credit": credit})
            print("OK ", cid, "|", en, "|", fr, "|", ar, "|", rel)
        except Exception as e:
            failed.append(f"{en}: {e}"); print("ERREUR", en, "->", e)
    used = sorted({i["category"] for i in items})
    out_cats = [{k: c[k] for k in ("id", "emoji", "ar", "fr", "en", "color")} for cid in used if (c := cats.get(cid))]
    os.makedirs(OUT, exist_ok=True)
    json.dump({"version": int(time.time()), "categories": out_cats, "items": items},
              open(JSON_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "CREDITS.md"), "w", encoding="utf-8") as f:
        f.write("# Crédits images\n\n" + "\n".join(f"- {i['category']}/{i['en']}: {i['credit']}" for i in items) + "\n")
    print(f"\n{len(items)} éléments OK, {len(failed)} échecs")
    for x in failed: print("  ✗", x)
    if not items:
        print("AUCUN élément généré: vérifie les erreurs ci-dessus (réseau, quota, words.csv, rembg)."); sys.exit(1)


if __name__ == "__main__":
    main()
