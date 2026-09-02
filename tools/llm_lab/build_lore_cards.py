#!/usr/bin/env python3
"""A11 lore card builder: era-scrub the corpus, emit the shipped jsonl.

Reads the wiki harvest at G:\\Wow llm stuff\\lore (592 pages, fetched
2026-08-19 from warcraft.wiki.gg), scrubs it to the Vanilla 1.12 frame
(Year 24), and writes one JSON line per surviving page:

    {"title": ..., "text": <~140-word era-scrubbed lead>, "keys": [...],
     "poi": true for zones/cities/dungeons}

The scrub implements the CORRECTED era policy: never
bare-ban Dalaran, death knight, worgen, Northrend, Outland, blood elf,
Lich King, Naxxramas or Kel'Thuzad - all 1.12-legitimate. Only
later-expansion SENSES die (expansion names, patch refs, post-2006
dates, later zones, playable-class premises, "Dalaran floats", ...).

The output is era-LINTED before writing; the host battery
(tools/test_llm_truth.cpp, run by tests/test_llm_truth.py) re-lints the
SHIPPED file with the C++ scanner, so python/C++ drift cannot ship a
contaminated card silently.

Usage: python tools/llm_lab/build_lore_cards.py [--out <path>] [--report]
"""
import argparse
import json
import os
import re
import sys
from collections import Counter

CORPUS = r"G:\Wow llm stuff\lore"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
DEFAULT_OUT = os.path.join(
    REPO, "android", "app", "src", "main", "assets", "lore",
    "lore_cards_v112.jsonl")

POI_CATEGORIES = {
    "zones_eastern_kingdoms", "zones_kalimdor", "cities", "dungeons_raids",
}

# Sections dropped whole (by header text, lowercase substring match).
DROP_SECTIONS = [
    "patch changes", "external links", "see also", "references",
    "gallery", "videos", "media", "trivia", "patch history",
]

# Expansion names and later-era works: any line or section header carrying
# one of these is post-1.12 (The Burning Crusade onward). Note "legion"
# alone is NOT here (the Burning Legion is vanilla); only the full title.
# NOTE: "dragonflight" (creature type) and "midnight" (ordinary word)
# are deliberately absent - they collide with vanilla vocabulary the same
# way "wrath" and "legion" do; only full expansion TITLES are markers.
EXPANSIONS = [
    "burning crusade", "wrath of the lich king", "cataclysm",
    "mists of pandaria", "warlords of draenor", "battle for azeroth",
    "shadowlands", "the war within",
]

# Later-expansion places/realms that cannot exist in a 1.12 frame.
LATER_PLACES = [
    "shattrath", "argus", "zereth mortis", "korthia", "the maw",
    "revendreth", "maldraxxus", "ardenweald", "bastion", "nazjatar",
    "kul tiras", "zandalar", "broken isles", "broken shore",
    "hellfire peninsula", "zangarmarsh", "terokkar forest",
    "shadowmoon valley", "blade's edge", "blades edge", "netherstorm",
    "highmountain", "stormheim", "suramar", "val'sharah", "azsuna",
    "dalaran floats", "sewers of dalaran", "dalaran's sewers",
    "ebon blade", "acherus",
    # Northrend zones (post-1.12 destinations; Northrend ITSELF stays
    # context-allow as the distant WC3 horror)
    "dragonblight", "howling fjord", "borean tundra", "icecrown",
    "zul'drak", "zul drak", "grizzly hills", "sholazar basin",
    "crystalsong forest", "storm peaks", "wintergrasp",
    # era tells: the WotLK-acronym and retail level framing
    "wotlk", "level-80", "level 80",
]

# Races/classes/senses that only exist post-1.12 as such.
LATER_SENSES = [
    "draenei", "pandaren", "worgen",  # worgen sense-checked below
    "playable", "hero class", "death knight",  # death knight checked below
    "flying mount", "portal is open", "portals are open",
    "gilneas opened", "gilneas is open", "gilneas has opened",
    "demon hunter", "monk class", "goblin",
    # wiki-retrofit tells: the lead rewritten from the LATER-era state
    # ("now known as Remains of Darnassus", "former Warchief", the
    # post-Cataclysm "High King" title)
    "now known as", "high king", "former warchief",
    # retrospective-arc tells (the page narrates from past expansions)
    "pandaria", "garrosh", "burning of teldrassil", "sons of lothar",
    # late-franchise content and framing that survived the section scrub
    "xal'atath", "khaz algar", "radiant song", "vanessa",
    "council of three hammers", "warcraft franchise", "anniversary",
]

# Context-allow words whose lines survive UNLESS a later sense co-occurs.
CONTEXT_ALLOW = {
    "dalaran", "death knight", "death knights", "northrend", "outland",
    "blood elf", "blood elves", "worgen", "lich king", "naxxramas",
    "kel'thuzad", "greymane", "gilneas", "wrath", "legion", "monk",
    "goblin", "goblins", "demon",
}


def line_is_post_era(line):
    """True when the line carries an unambiguous post-1.12 marker."""
    low = line.lower()
    # wiki editorial banners never belong in a card
    if low.startswith("this section") or "concerns content related to" in low:
        return True
    if low.startswith("the subject of this article") or "out-of-game" in low:
        return True
    if "please add any available information" in low or "this article is a stub" in low:
        return True
    # expansion titles / patch refs / post-2006 dates: always post-era
    for marker in EXPANSIONS:
        if marker in low:
            return True
    if re.search(r"\bpatch \d+\.\d+", low):
        return True
    if re.search(r"\(20(?:0[7-9]|[1-9]\d)\)", low):  # (2007)-(2099)
        return True
    if re.search(r"\bhotfix\b|\bptr\b|\bbeta\b", low):
        return True
    for place in LATER_PLACES:
        if place in low:
            return True
    # goblin/demon/monk/worgen/etc. have vanilla senses; only the
    # later-expansion co-senses kill the line
    for sense in LATER_SENSES:
        if sense not in low:
            continue
        if sense in ("worgen", "gilneas opened", "gilneas is open"):
            # worgen ARE vanilla Silverpine; the line dies only with a
            # later-work co-marker
            continue
        if sense in ("playable", "hero class"):
            # "playable"/"hero class" alone is metagame speak: post-era
            return True
        if sense in ("death knight",):
            # vanilla senses: Four Horsemen, Rivendare, Gorefiend, Teron
            vanilla = any(v in low for v in (
                "four horsemen", "rivendare", "gorefiend", "baron",
                "thane", "death knight pony", "second war", "third war",
                "warcraft iii", "warcraft 3", "frozen throne"))
            if vanilla:
                continue
            return True
        return True
    # "flying mount" phrase (kept out of LATER_SENSES loop for clarity)
    if "flying mount" in low:
        return True
    if re.search(r"portal (is|are|has|have) (open|opened)", low):
        return True
    if re.search(r"gilneas (opened|is open|has opened)", low):
        return True
    return False


def scrub_page(text):
    """Section- and line-level scrub. Returns (kept_text, stats).."""
    stats = Counter()
    lines = text.split("\n")
    out = []
    drop_section = False
    for line in lines:
        header = re.match(r"^==+\s*(.+?)\s*=+=$", line)
        if header:
            title = header.group(1).lower()
            drop_section = any(d in title for d in DROP_SECTIONS) or \
                line_is_post_era(header.group(1))
            stats["sections_dropped" if drop_section else "sections_kept"] += 1
            continue  # headers never reach the card text
        if drop_section:
            stats["lines_in_dropped_sections"] += 1
            continue
        if line_is_post_era(line):
            stats["lines_dropped"] += 1
            continue
        out.append(line)
        stats["lines_kept"] += 1
    return "\n".join(out), stats


STOPWORDS = {
    "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or",
    "is", "are", "was", "were", "with", "by", "from", "that", "this",
}


def normalize_title_words(title):
    words = [w for w in re.split(r"[^a-z']+", title.lower()) if w]
    return [w for w in words if w not in STOPWORDS and len(w) > 2]


def build_keys(title, category, scrubbed):
    keys = set()
    low = title.lower().strip()
    keys.add(low)
    bare = re.sub(r",\s*the$", "", low)
    if bare.startswith("the "):
        keys.add(bare[4:])
    words = normalize_title_words(title)
    for w in words:
        keys.add(w)
    if len(words) > 1:
        keys.add(" ".join(words))
    # zone mention: dungeons/cities carry their zone as an alias so
    # "dungeon in westfall" and zone-scoped questions retrieve them.
    # First-mentioned order, capped at TWO - a city page name-drops half
    # the map and every extra alias dilutes both retrieval and POI
    # resolution
    if category in ("dungeons_raids", "cities"):
        low = scrubbed.lower()
        ranked = sorted(ZONE_TITLES, key=lambda z: low.find(z))
        taken = 0
        for zone in ranked:
            if zone in low and taken < 2:
                keys.add(zone)
                taken += 1
    return sorted(k for k in keys if k and len(k) > 2)


def card_text(scrubbed, word_target=140):
    """The lead at FULL density: whole sentences up to ~140 words.

    Paragraphs assemble in order; an era-marked paragraph ENDS the
    assembly once the card already has ~40 words - prose after an era
    drop is the wiki's retrospective narrative, not vanilla truth.
    (The scrub already removed marked lines from `scrubbed`; the stop
    rule here re-checks each paragraph so a later-era interruption
    cannot be assembled around.)
    """
    prose = []
    for line in scrubbed.split("\n"):
        s = line.strip()
        if not s or s.startswith(("*", ":", "|", "{", "}", "<")):
            continue
        s = re.sub(r"\s+", " ", s).strip()
        if len(s.split()) < 3:
            continue
        prose.append(s)
    words = []
    for s in prose:
        if len(words) >= 40 and line_is_post_era(s):
            break
        for piece in re.split(r"(?<=[.!?])\s+", s):
            piece = piece.strip()
            if not piece:
                continue
            n = len(piece.split())
            if len(words) and len(words) + n > word_target + 20:
                break
            words.extend(piece.split())
            if len(words) >= word_target:
                break
        if len(words) >= word_target:
            break
    return " ".join(words)


ZONE_CATEGORIES = {"zones_eastern_kingdoms", "zones_kalimdor"}
ZONE_TITLES = []  # filled on the first pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--report", action="store_true")
    args = ap.parse_args()

    pages = []
    for category in sorted(os.listdir(CORPUS)):
        catdir = os.path.join(CORPUS, category)
        if not os.path.isdir(catdir):
            continue
        for name in sorted(os.listdir(catdir)):
            if not name.endswith(".txt"):
                continue
            path = os.path.join(catdir, name)
            with open(path, encoding="utf-8", errors="replace") as f:
                raw = f.read()
            # file header: title / Source: / Category: lines
            header_lines = raw.split("\n")
            title = header_lines[0].strip() if header_lines else name[:-4]
            pages.append((category, title, raw))

    # first pass: zone titles (for zone-alias keys on dungeons/cities)
    for category, title, _ in pages:
        if category in ZONE_CATEGORIES:
            bare = re.sub(r",\s*the$", "", title.lower()).strip()
            if bare.startswith("the "):
                bare = bare[4:]
            ZONE_TITLES.append(bare)

    total_stats = Counter()
    cards = []
    for category, title, raw in pages:
        # a page TITLED with a later-era subject is framed post-1.12
        # (Draenei, Zandalar, Kul Tiras): skip it whole - the guard
        # handles those names as unknowns, no card should ground them
        if line_is_post_era(title):
            total_stats["pages_skipped_later_era_title"] += 1
            continue
        scrubbed, stats = scrub_page(raw)
        total_stats.update(stats)
        text = card_text(scrubbed)
        if len(text.split()) < 25:
            total_stats["pages_too_thin"] += 1
            continue
        cards.append({
            "title": title,
            "text": text,
            "keys": build_keys(title, category, scrubbed),
            "poi": category in POI_CATEGORIES,
        })

    # era lint (mirror of the C++ EraScan over the shipped content; the
    # C++ host battery re-checks the file itself)
    def era_lint(card):
        low = (card["title"] + "\n" + card["text"]).lower()
        for term in ("shattrath", "draenei", "pandaren", "acherus"):
            if re.search(rf"\b{term}\b", low):
                return term
        phrases = [
            "flying mount", "portal is open", "portals are open",
            "gilneas opened", "gilneas is open", "playable worgen",
            "dalaran floats", "sewers of dalaran", "ebon blade",
            "burning crusade", "wrath of the lich king", "cataclysm",
            "mists of pandaria", "warlords of draenor", "shadowlands",
            "battle for azeroth", "the war within",
            "broken isles", "hellfire peninsula", "nazjatar",
            "kul tiras", "zandalar", "revendreth", "maldraxxus",
            "ardenweald", "zereth mortis", "korthia", "argus",
        ]
        for p in phrases:
            if p in low:
                return p
        return None

    violations = []
    for card in cards:
        hit = era_lint(card)
        if hit:
            violations.append((card["title"], hit))
    if violations:
        print("ERA LINT FAILURES:")
        for title, hit in violations:
            print(f"  [{title}] carried '{hit}'")
        sys.exit(1)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        for card in cards:
            f.write(json.dumps(card, ensure_ascii=True,
                               separators=(",", ":")) + "\n")

    print(f"pages read:        {len(pages)}")
    print(f"cards written:     {len(cards)}")
    print(f"poi cards:         {sum(1 for c in cards if c['poi'])}")
    for k in sorted(total_stats):
        print(f"{k}: {total_stats[k]}")
    print(f"out: {args.out} ({os.path.getsize(args.out)} bytes)")
    if args.report:
        cats = Counter()
        for category, _, _ in pages:
            cats[category] += 1
        for c, n in sorted(cats.items()):
            print(f"  {c}: {n}")


if __name__ == "__main__":
    main()
