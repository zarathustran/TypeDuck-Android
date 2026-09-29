#!/usr/bin/env python3
"""Generate Traditional Mandarin Rime dictionaries for TypeDuck Mandarin."""

from __future__ import annotations

import argparse
import csv
import io
import re
import urllib.request
from pathlib import Path

CEDICT_URL = (
    "https://raw.githubusercontent.com/cschiller/zhongwen/"
    "e6b46b6fc9a05655eefa7fb8e6b12be93e6f1618/data/cedict_ts.u8"
)
SOURCE_LABEL = "CC-CEDICT snapshot mirrored by cschiller/zhongwen, commit e6b46b6 (2026-09-27)"
RIME_ICE_COMMIT = "3aea6d3694fb3d94ec663641f021f788822897ad"
RIME_ICE_URLS = (
    f"https://raw.githubusercontent.com/iDvel/rime-ice/{RIME_ICE_COMMIT}/cn_dicts/8105.dict.yaml",
    f"https://raw.githubusercontent.com/iDvel/rime-ice/{RIME_ICE_COMMIT}/cn_dicts/base.dict.yaml",
)
RIME_ICE_SOURCE_LABEL = f"Rime-Ice frequency data, commit {RIME_ICE_COMMIT[:7]} (2026-09-25)"

ENTRY_RE = re.compile(
    r"^(?P<trad>\S+)\s+(?P<simp>\S+)\s+"
    r"(?:\[\[(?P<v2>[^]]+)\]\]|\[(?P<v1>[^]]+)\])\s+/(?P<defs>.*)/$"
)
TONE_RE = re.compile(r"[1-5]")
BOUNDARY_RE = re.compile(r"([1-5])(?=[A-Za-züÜ])")
NON_PINYIN_WITH_TONES_RE = re.compile(r"[^a-zv1-5]+")
NON_PINYIN_RE = re.compile(r"[^a-zv]+")
CANTONESE_PREFIXES = ("jyut6ping3", "loengfan")


def contains_cjk(text: str) -> bool:
    return any(
        "\u3400" <= ch <= "\u9fff"
        or "\U00020000" <= ch <= "\U0003134f"
        for ch in text
    )


def normalize_tone_pinyin(raw: str) -> str:
    # CC-CEDICT V1 uses spaces between syllables; V2 may join numbered syllables.
    raw = BOUNDARY_RE.sub(r"\1 ", raw)
    raw = raw.replace("u:", "v").replace("U:", "v").replace("ü", "v").replace("Ü", "v")
    raw = raw.lower()
    raw = NON_PINYIN_WITH_TONES_RE.sub(" ", raw)
    return " ".join(raw.split())


def input_pinyin(tone_pinyin: str) -> str:
    return " ".join(TONE_RE.sub("", tone_pinyin).split())


def clean_definition(raw: str) -> str:
    senses = []
    for sense in raw.split("/"):
        sense = " ".join(sense.replace("\t", " ").replace("\r", " ").replace("\n", " ").split())
        if not sense:
            continue
        # Keep the candidate strip useful instead of dumping the entire dictionary article.
        senses.append(sense)
        if len(senses) == 3:
            break
    definition = "; ".join(senses)
    # Rime's sync/config scanner also opens *.dict.yaml files as YAML. Dictionary
    # entries are TSV data after the YAML header, but a plain-scalar ": " or " #"
    # in a gloss can still confuse yaml-cpp before the dictionary compiler sees it.
    # Use visually clear Unicode punctuation that is harmless to both parsers.
    definition = definition.replace(": ", "： ").replace(" #", " №")
    return definition[:240]


def parse_entries(text: str) -> list[tuple[str, str, str, str, str]]:
    seen: set[tuple[str, str]] = set()
    entries: list[tuple[str, str, str, str, str]] = []
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = ENTRY_RE.match(line)
        if not match:
            continue
        traditional = match.group("trad")
        simplified = match.group("simp")
        if not contains_cjk(traditional):
            continue
        tone_pinyin = normalize_tone_pinyin(match.group("v2") or match.group("v1") or "")
        plain_pinyin = input_pinyin(tone_pinyin)
        if not plain_pinyin:
            continue
        key = (traditional, plain_pinyin)
        if key in seen:
            continue
        seen.add(key)
        entries.append(
            (
                traditional,
                simplified,
                plain_pinyin,
                tone_pinyin,
                clean_definition(match.group("defs")),
            )
        )
    return entries


def csv_row(fields: list[str], force_quote_indexes: set[int] | None = None) -> str:
    """Encode TypeDuck's comma-separated candidate metadata.

    The native lookup filter expects the first two fields to remain unquoted because it
    extracts them with string::find(',') and std::stoi(). Later fields are parsed as CSV
    by the Android candidate layer, so we can force-quote the English gloss to keep
    characters such as ': ' from confusing librime's YAML config scanner.
    """
    force_quote_indexes = force_quote_indexes or set()

    def encode(index: int, value: str) -> str:
        must_quote = (
            index in force_quote_indexes
            or "," in value
            or '"' in value
            or "\r" in value
            or "\n" in value
        )
        if not must_quote:
            return value
        return '"' + value.replace('"', '""') + '"'

    return ",".join(encode(index, value) for index, value in enumerate(fields))


def build_lookup_row(
    traditional: str,
    plain_pinyin: str,
    tone_pinyin: str,
    definition: str,
) -> str:
    # The native rime-dictionary-lookup-filter prepends matchInputBuffer and
    # honzi before handing this CSV to CandidateEntry. Therefore these fields
    # begin at CandidateEntry.jyutping:
    #   0 pronunciation, 1 pronOrder, 2 sandhi, 3 litColReading,
    #   4 partOfSpeech, 5 register, 6 label, 7 normalized, 8 written,
    #   9 vernacular, 10 collocation, 11 English, 12 Urdu, 13 Nepali,
    #   14 Hindi, 15 Indonesian.
    #
    # The native filter calls std::stoi() on field 1, so it must always contain
    # a valid integer. Leaving it blank crashes the IME process when candidates
    # are looked up.
    fields = [""] * 16
    fields[0] = plain_pinyin.replace(" ", "")
    fields[1] = "1"
    fields[2] = "0"
    fields[11] = definition

    row = csv_row(fields, force_quote_indexes={11})
    parsed = next(csv.reader([row]))
    if len(parsed) != len(fields) or not parsed[1].isdigit():
        raise RuntimeError("Generated an invalid TypeDuck lookup row")
    return f"{row}\t{traditional}"


def download_text(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "TypeDuck-Mandarin dictionary builder"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read().decode("utf-8")


def download_cedict() -> str:
    return download_text(CEDICT_URL)


def normalize_rime_ice_pinyin(raw: str) -> str:
    raw = raw.replace("u:", "v").replace("U:", "v").replace("ü", "v").replace("Ü", "v")
    raw = NON_PINYIN_RE.sub(" ", raw.lower())
    return " ".join(raw.split())


def parse_rime_ice_weights(text: str) -> dict[tuple[str, str], int]:
    weights: dict[tuple[str, str], int] = {}
    in_body = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == "...":
            in_body = True
            continue
        if not in_body or not stripped or stripped.startswith("#"):
            continue

        fields = line.split("\t")
        if len(fields) < 3:
            continue
        word = fields[0].strip()
        pinyin = normalize_rime_ice_pinyin(fields[1])
        if not word or not pinyin:
            continue
        try:
            weight = max(1, int(float(fields[2].strip())))
        except ValueError:
            continue

        key = (word, pinyin)
        weights[key] = max(weight, weights.get(key, 0))
    return weights


def load_frequency_weights() -> dict[tuple[str, str], int]:
    weights: dict[tuple[str, str], int] = {}
    for url in RIME_ICE_URLS:
        for key, weight in parse_rime_ice_weights(download_text(url)).items():
            weights[key] = max(weight, weights.get(key, 0))
    if len(weights) < 50_000:
        raise RuntimeError(
            f"Parsed only {len(weights)} Rime-Ice weighted entries; refusing to build weak ranking data"
        )
    return weights


def entry_weight(
    traditional: str,
    simplified: str,
    plain_pinyin: str,
    frequency_weights: dict[tuple[str, str], int],
) -> int:
    return max(
        frequency_weights.get((simplified, plain_pinyin), 0),
        frequency_weights.get((traditional, plain_pinyin), 0),
        1,
    )


def patch_file(path: Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new in replacements:
        if old not in text:
            raise RuntimeError(f"Expected text not found in {path}: {old!r}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "rime_dir",
        nargs="?",
        default="app/src/main/assets/rime",
        type=Path,
    )
    args = parser.parse_args()
    rime_dir: Path = args.rime_dir

    raw = download_cedict()
    entries = parse_entries(raw)
    if len(entries) < 100_000:
        raise RuntimeError(f"Parsed only {len(entries)} entries; refusing to build an incomplete dictionary")

    frequency_weights = load_frequency_weights()
    weighted_count = sum(
        1
        for traditional, simplified, plain_pinyin, _, _ in entries
        if entry_weight(traditional, simplified, plain_pinyin, frequency_weights) > 1
    )
    if weighted_count < 20_000:
        raise RuntimeError(
            f"Only {weighted_count} CEDICT entries received frequency weights; refusing weak ranking data"
        )

    dictionary = [
        "# Rime dictionary",
        "# encoding: utf-8",
        "#",
        "# Generated by script/build-mandarin-dictionary.py",
        f"# Source: {SOURCE_LABEL}",
        f"# Ranking: {RIME_ICE_SOURCE_LABEL}",
        "# Data licenses: CC BY-SA 4.0 + applicable Rime-Ice GPLv3 terms (see MANDARIN_DICTIONARY.md)",
        "",
        "---",
        "name: luna_pinyin",
        'version: "2026.09.29-cedict-r4"',
        "sort: by_weight",
        "...",
        "",
    ]
    dictionary.extend(
        f"{traditional}\t{plain_pinyin}\t"
        f"{entry_weight(traditional, simplified, plain_pinyin, frequency_weights)}"
        for traditional, simplified, plain_pinyin, _, _ in entries
    )
    (rime_dir / "luna_pinyin.dict.yaml").write_text("\n".join(dictionary) + "\n", encoding="utf-8")

    lookup_dictionary = [
        "# TypeDuck English-gloss lookup dictionary generated from CC-CEDICT",
        "# encoding: utf-8",
        "---",
        "name: mandarin_cedict_lookup",
        'version: "2026.09.29-cedict-r4"',
        "sort: original",
        "use_preset_vocabulary: false",
        "...",
        "",
    ]
    lookup_dictionary.extend(
        build_lookup_row(traditional, plain_pinyin, tone_pinyin, definition)
        for traditional, _, plain_pinyin, tone_pinyin, definition in entries
        if definition
    )
    (rime_dir / "mandarin_cedict_lookup.dict.yaml").write_text(
        "\n".join(lookup_dictionary) + "\n",
        encoding="utf-8",
    )

    (rime_dir / "mandarin_cedict_lookup.schema.yaml").write_text(
        """# Rime schema used only to compile TypeDuck's English-gloss lookup dictionary
schema:
  schema_id: mandarin_cedict_lookup
  name: Mandarin CEDICT Lookup
  version: "2026.09.29-r4"

switches:
  - name: ascii_mode
    reset: 0
    states: [ 中文, 英文 ]

engine:
  processors:
    - ascii_composer
  segmentors:
    - ascii_segmentor
  translators:
    - table_translator

speller:
  alphabet: zyxwvutsrqponmlkjihgfedcba
  delimiter: " '"

translator:
  dictionary: mandarin_cedict_lookup
""",
        encoding="utf-8",
    )

    (rime_dir / "default.custom.yaml").write_text(
        """# TypeDuck Mandarin: expose only Traditional Mandarin Pinyin
patch:
  schema_list:
    - schema: luna_pinyin
  menu:
    page_size: 50
""",
        encoding="utf-8",
    )

    patch_file(
        rime_dir / "default.yaml",
        [("  - schema: jyut6ping3", "  - schema: luna_pinyin")],
    )
    patch_file(
        rime_dir / "template.yaml",
        [("  dictionary: jyut6ping3_scolar", "  dictionary: mandarin_cedict_lookup")],
    )
    patch_file(
        rime_dir / "luna_pinyin.schema.yaml",
        [
            (
                "  name: 普通話\n  author:",
                "  name: 國語拼音（繁體）\n  dependencies:\n    - mandarin_cedict_lookup\n  author:",
            ),
            ("  enable_sentence: false", "  enable_sentence: true"),
            ("  enable_user_dict: false", "  enable_user_dict: true"),
            (
                "  encode_commit_history: false",
                "  encode_commit_history: true\n"
                "  contextual_suggestions: true\n"
                "  enable_completion: true",
            ),
            (r"    - xform/^/\v/", r"    - xform/^/\f/"),
        ],
    )
    patch_file(
        rime_dir / "trime.yaml",
        [
            ("  locale: zh_HK", "  locale: zh_TW"),
            ("  horizontal_gap: 5", "  horizontal_gap: 4"),
            ("  keyboard_padding: 5", "  keyboard_padding: 4"),
            ("  key_height: 44", "  key_height: 54"),
            ("  round_corner: 16", "  round_corner: 10"),
            ("  vertical_gap: 10", "  vertical_gap: 6"),
        ],
    )
    trime_path = rime_dir / "trime.yaml"
    trime_text = trime_path.read_text(encoding="utf-8")
    trime_text = trime_text.replace("    height: 44", "    height: 54")
    trime_path.write_text(trime_text, encoding="utf-8")

    for path in rime_dir.iterdir():
        if path.is_file() and path.name.startswith(CANTONESE_PREFIXES):
            path.unlink()

    gloss_count = sum(1 for _, _, _, _, definition in entries if definition)
    print(
        f"Generated {len(entries)} Traditional Mandarin entries and "
        f"{gloss_count} English-gloss rows from {SOURCE_LABEL}; "
        f"frequency-ranked {weighted_count} entries using {RIME_ICE_SOURCE_LABEL}"
    )


if __name__ == "__main__":
    main()