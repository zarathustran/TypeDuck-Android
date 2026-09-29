# Traditional Mandarin dictionary

This fork changes TypeDuck from a Cantonese-first IME into a Traditional Chinese Mandarin Pinyin IME.

## Requested source and provenance

The requested reference is [DICT.TW's CEDICT database](https://dict.tw/database/cedict). DICT.TW documents its database as a Taiwanese-Mandarin localization of CC-CEDICT, using Traditional Chinese headwords and adjusted pronunciation/notes. Its published database page is a query service and does not provide a bulk export file.

For reproducible offline builds, this project therefore builds the Rime lexicon from a pinned CC-CEDICT snapshot mirrored in the open-source Zhongwen project:

- Snapshot commit: `e6b46b6fc9a05655eefa7fb8e6b12be93e6f1618` (2026-09-27)
- Data file: `data/cedict_ts.u8`
- Build script: `script/build-mandarin-dictionary.py`

The converter keeps each entry's **Traditional Chinese headword** and converts numbered Hanyu Pinyin to Rime's untone-numbered input spelling. Simplified headwords are used only as a build-time key for frequency matching; English definitions are packaged separately for the candidate gloss UI.

## Candidate ranking

To improve common-word ordering without replacing the Traditional CEDICT lexicon, the build reads two frequency tables from the GPLv3-licensed [Rime-Ice](https://github.com/iDvel/rime-ice) project and applies those weights only when both the headword and Pinyin match a CEDICT entry:

- Rime-Ice commit: `3aea6d3694fb3d94ec663641f021f788822897ad` (2026-09-25)
- Tables: `cn_dicts/8105.dict.yaml` and `cn_dicts/base.dict.yaml`

No Rime-Ice-only words are added to the TypeDuck candidate lexicon, so the Traditional output and CEDICT English-gloss coverage stay intact. Rime's user dictionary, commit history, contextual suggestions, sentence composition, and completion are enabled so ranking can adapt as the keyboard is used.

## Licensing

Current CC-CEDICT data is distributed under **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)**. Generated `luna_pinyin.dict.yaml` contains CC-CEDICT-derived entries plus matching frequency weights derived from Rime-Ice; the applicable CC BY-SA 4.0 and Rime-Ice GPLv3 terms are retained here with source attribution.

DICT.TW's published localized 2019 database notice identifies its snapshot as CC BY-SA 3.0 and says no additional restrictions are claimed. This repository does **not** claim that the generated lexicon contains DICT.TW's unpublished bulk-localization changes; DICT.TW is retained here as the requested Taiwanese-Mandarin reference and provenance note.

The Android application source remains under the upstream TypeDuck project's GPL license; see `LICENSE`.