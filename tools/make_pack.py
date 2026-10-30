#!/usr/bin/env python3
"""Génère pack/items.json + pack/images/*.jpg à partir de tools/words.csv.

Images  : Pixabay (si secret PIXABAY_KEY) -> Wikipedia -> Openverse (essayés dans cet ordre)
Traduction fr/ar vides : Google (gtx, non officiel) puis MyMemory en secours
Options : --refresh  ignore les anciennes traductions du pack et les recalcule
Sortie  : code 1 si aucun élément n'a pu être généré (le workflow devient rouge)."""
import csv, io, itertools, json, os, re, sys, time, traceback
import urllib.error, urllib.parse, urllib.request
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "pack"); IMG = os.path.join(OUT, "images")
JSON_PATH = os.path.join(OUT, "items.json")
REPO = os.environ.get("GITHUB_REPOSITORY", "local")
UA = {"User-Agent": f"KidsCarouselPack/1.0 (https://github.com/{REPO}; educational use)"}
PIXABAY_KEY = os.environ.get("PIXABAY_KEY", "").strip()
MM_EMAIL = os.environ.get("MYMEMORY_EMAIL", "").strip()
REFRESH = "--refresh" in sys.argv
COLORS = {"fruits": "#FFE0E0", "animaux": "#FFF2CC", "vehicules": "#DDEBFF",
          "objets": "#E2F5E2", "nature": "#D8F3F7"}
AR = re.compile("[\u0600-\u06FF]")


class Quota(Exception):
    """Limite de requêtes atteinte: la source est désactivée pour le reste de l'exécution."""


def http(url, tries=3):
    last = None
    for n in range(tries):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()
        except urllib.error.HTTPError as e:
            if e.code == 429:
                raise Quota(url.split("?")[0])
            if e.code in (400, 401, 403, 404):
                raise
            last = e
        except Exception as e:
            last = e
        time.sleep(2 * (n + 1))
    raise last


# ---------------------------------------------------------------- traduction
def tr_google(text, to):
    q = urllib.parse.urlencode({"client": "gtx", "sl": "en", "tl": to, "dt": "t", "q": text})
    d = json.loads(http("https://translate.googleapis.com/translate_a/single?" + q))
    return "".join(p[0] for p in d[0] if p and p[0]).strip()


def tr_mymemory(text, to):
    params = {"q": text, "langpair": f"en|{to}"}
    if MM_EMAIL: params["de"] = MM_EMAIL
    d = json.loads(http("https://api.mymemory.translated.net/get?" + urllib.parse.urlencode(params)))
    if str(d.get("responseStatus")) != "200":
        raise RuntimeError(f"MyMemory {d.get('responseStatus')}: {d.get('responseDetails')}")
    t = d["responseData"]["translatedText"].strip()
    if "MYMEMORY" in t.upper(): raise RuntimeError("quota MyMemory atteint")
    return t


def valid(t, lang):
    if not t or "MYMEMORY" in t.upper(): return False
    return bool(AR.search(t)) if lang == "ar" else not AR.search(t)


def translate(text, to):
    errs = []
    for fn in (tr_google, tr_mymemory):
        try:
            t = fn(text, to)
            t = t if to == "ar" else t.lower()
            if valid(t, to): return t
            errs.append(f"{fn.__name__}: réponse inutilisable ({t!r})")
        except Exception as e:
            errs.append(f"{fn.__name__}: {e}")
    raise RuntimeError("traduction impossible - " + " | ".join(errs))


# -------------------------------------------------------------------- images
def cands_pixabay(q):
    p = urllib.parse.urlencode({"key": PIXABAY_KEY, "q": q, "image_type": "photo", "safesearch": "true",
                                "per_page": 8, "min_width": 400})
    for h in json.loads(http("https://pixabay.com/api/?" + p)).get("hits", []):
        yield h.get("webformatURL"), f"Pixabay - {h.get('user')} - {h.get('pageURL')} (Pixabay Content License)"


def cands_wikipedia(q):
    p = urllib.parse.urlencode({"action": "query", "generator": "search", "gsrsearch": q, "gsrnamespace": 0,
                                "gsrlimit": 8, "prop": "pageimages", "piprop": "thumbnail",
                                "pithumbsize": 640, "format": "json"})
    pages = json.loads(http("https://en.wikipedia.org/w/api.php?" + p)).get("query", {}).get("pages", {})
    for pg in sorted(pages.values(), key=lambda x: x.get("index", 99)):
        th = pg.get("thumbnail", {}).get("source")
        if th:
            t = pg["title"]
            yield th, f"Wikipedia - {t} - https://en.wikipedia.org/wiki/{urllib.parse.quote(t.replace(' ', '_'))} (licence: voir la page)"


def cands_openverse(q):
    p = urllib.parse.urlencode({"q": q, "license_type": "commercial", "mature": "false", "page_size": 8})
    for r in json.loads(http("https://api.openverse.org/v1/images/?" + p)).get("results", []):
        if (r.get("width") or 0) >= 400:
            yield r["url"], (f'{r.get("title")} - {r.get("creator")} - {(r.get("license") or "").upper()} '
                             f'{r.get("license_version") or ""} - {r.get("foreign_landing_url")}')


SOURCES = ([("pixabay", cands_pixabay)] if PIXABAY_KEY else []) + \
          [("wikipedia", cands_wikipedia), ("openverse", cands_openverse)]
disabled = set()


def candidates(query):
    for name, fn in SOURCES:
        if name in disabled: continue
        try:
            for url, credit in fn(query):
                if url: yield url, credit
        except Quota as e:
            print(f"  ! limite atteinte pour {name} ({e}), source désactivée"); disabled.add(name)
        except Exception as e:
            print(f"  ! {name}: {type(e).__name__}: {e}")


def to_jpeg(data):
    im = Image.open(io.BytesIO(data)); im.load()
    if im.mode in ("RGBA", "LA", "P"):
        im = im.convert("RGBA"); bg = Image.new("RGB", im.size, "white"); bg.paste(im, mask=im.split()[-1]); im = bg
    else:
        im = im.convert("RGB")
    if min(im.size) < 250: raise ValueError("image trop petite")
    im.thumbnail((512, 512)); return im


def fetch_image(query, dest):
    for url, credit in itertools.islice(candidates(query), 10):
        try:
            to_jpeg(http(url)).save(dest, "JPEG", quality=85)
            return credit
        except Exception as e:
            print(f"  - image refusée ({type(e).__name__}: {e}) {url[:80]}")
    return None


# ----------------------------------------------------------------------- CSV
def read_words():
    path = os.path.join(ROOT, "tools", "words.csv")
    text = open(path, encoding="utf-8-sig").read()          # utf-8-sig: supprime le BOM d'Excel/Bloc-notes
    lines = [l for l in text.splitlines() if l.strip() and not l.lstrip().startswith("#")]
    if not lines: return []
    delim = max(",;\t", key=lines[0].count)                   # Excel FR enregistre avec des ';'
    rows = list(csv.reader(lines, delimiter=delim))
    header = [h.strip().lower() for h in rows[0]]
    if "en" in header: data = rows[1:]
    else: header, data = ["en", "fr", "ar", "category", "query"], rows   # fichier sans en-tête
    return [{header[i]: r[i].strip() for i in range(min(len(header), len(r)))} for r in data]


# ---------------------------------------------------------------------- main
def main():
    os.makedirs(IMG, exist_ok=True)
    old = {}
    if os.path.exists(JSON_PATH) and not REFRESH:
        try: old = {i["id"]: i for i in json.load(open(JSON_PATH, encoding="utf-8"))["items"]}
        except Exception: old = {}
    items, failed = [], []
    print("Sources d'images:", [n for n, _ in SOURCES])
    for row in read_words():
        en = row.get("en", "").strip()
        if not en: continue
        pid = re.sub(r"[^a-z0-9]+", "_", en.lower()).strip("_")
        prev = old.get(pid, {})
        try:
            def pick(key, lang):
                for v in (row.get(key, ""), prev.get(key, "")):
                    if v and valid(v, lang) if key == "ar" else v and "MYMEMORY" not in v.upper(): return v
                return translate(en, lang)
            fr, ar = pick("fr", "fr"), pick("ar", "ar")
            rel = f"images/{pid}.jpg"; dest = os.path.join(OUT, rel); credit = prev.get("credit", "")
            if not os.path.exists(dest):
                credit = fetch_image(row.get("query") or en, dest)
                if not credit: raise RuntimeError("aucune image téléchargeable")
                time.sleep(1)
            items.append({"id": pid, "img": rel, "ar": ar, "fr": fr, "en": en,
                          "color": COLORS.get(row.get("category", ""), "#EEEEEE"), "credit": credit})
            print("OK ", en, "|", fr, "|", ar)
        except Exception as e:
            failed.append(f"{en}: {e}"); print("ERREUR", en, "->", e)
            if os.environ.get("DEBUG"): traceback.print_exc()
    json.dump({"version": int(time.time()), "items": items}, open(JSON_PATH, "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "CREDITS.md"), "w", encoding="utf-8") as f:
        f.write("# Crédits images\n\n" + "\n".join(f"- {i['en']}: {i['credit']}" for i in items) + "\n")
    print(f"\n{len(items)} éléments OK, {len(failed)} échecs")
    for x in failed: print("  ✗", x)
    if not items:
        print("AUCUN élément généré: vérifie les erreurs ci-dessus (réseau, quota, words.csv).")
        sys.exit(1)


if __name__ == "__main__":
    main()
