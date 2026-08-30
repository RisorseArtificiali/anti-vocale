# Model Tab Curated Profiles: Design

Date: 2026-08-30. Status: approved design, pending backlog decomposition (no implementation scheduled). Origin: GH #60 seed (maintainer's idea, not derei's proposal: his static device-tier taxonomy was rejected; the dynamic-catalog infrastructure made a data-driven alternative possible). Brainstorm session with visual companion, mockups in `.superpowers/brainstorm/4190502-1788087683/` (gitignored).

## Problem

The Model tab presents one static, universally identical organization. The primary slots reflect an Italian-market tilt (Distil Italian's badge, Parakeet's recommended default); a German user's strongest options (German Whisper fine-tune, NeMo Flash de, Kroko DE) are buried in the Advanced import flow. Meanwhile the project ships two catalog sources: the bundled static catalog and the runtime-fetchable, user-overridable community index (TASK-401). The declared reality is the variance/complexity matrix (device x backend x model x language, README "How this project is built"): any curation must be data-driven and evolvable, not a hardcoded device or country taxonomy.

## Decision summary

- **Profiles** are named, curated organizations of the Model tab: sections in a declared order, each referencing models (bundled or community) with an optional badge and an optional intro paragraph.
- The mechanism is **generic** (any criterion a catalog author wants); WE publish **language profiles** in the official index. Third-party indexes may carry their own (country, hardware class, anything).
- The **language filter stays the primary tool for everyone**, including minority languages that will never have a page. Profiles surface only as an elevation (option D1: badge in the dropdown + one-tap banner after the language is selected). Never automatic.
- Profiles live **inside the existing index.json** (additive `profiles` key), so importing a catalog that carries profiles changes what the tab can offer. No new fetch infrastructure.

## Data model

Additive key in `index.json`:

```json
"profiles": [
  {
    "id": "de",
    "name": "Deutsch, kuratiert",
    "language": "de",
    "intro": "Für Deutsch optimierte Auswahl: ...",
    "sections": [
      { "title": "Empfohlen", "entries": ["german-whisper"], "badge": "Empfohlen für Deutsch" },
      { "title": "Kompakt", "entries": ["canary-german", "kroko-de"] },
      { "title": "Dialekte", "entries": ["swiss-german"] },
      { "title": "Mehrsprachig", "entries": ["sherpa-onnx", "whisper"] }
    ]
  }
]
```

Rules:
- `entries` strings resolve first against the index's own entry names/ids (community), then against bundled catalog entry ids (`sherpa-onnx`, `whisper`, `qwen3-asr`, `gigaam`, `nemotron-streaming`). One reference kind covers both worlds.
- `language`: single ISO code (the selector is the existing language filter).
- `intro`: optional plain string, rendered under the profile title. v1 is a plain string; a locale map can extend it later without breaking the format.
- No styling/layout fields in v1 beyond section title + entries + one optional badge per section.
- An index without `profiles` behaves exactly as today. Old clients ignore the key.

Sanity limits (parse time): max 20 sections per profile, 50 entries per section, 500 chars for name/intro/titles/badge.

## Resolution (ProfileResolver)

New `data/catalog/ProfileResolver`. One public function: given the active index set, return available profiles. Sources by priority:

1. The bundled official index (asset `index.json`).
2. The active community index (the TASK-401 fetchable, cached, user-overridable index).

Merge rule: per `language`, the highest-priority source wins (official beats third party). Duplicate ids across indexes: same rule. No deep section merging in v1. Lookup is synchronous over in-memory data (both indexes are already loaded for existing features; no new fetches).

## UI behavior (option D1)

State: the tab's existing ephemeral `filterLanguageCode` plus a new `activeProfile: CatalogProfile?` (null = universal tab). No persisted preference; profile is a view state. Interactions:

1. **Dropdown**: languages with an available profile carry a star badge next to the endonym. Selecting any language (badged or not) filters the list exactly as today.
2. **Banner**: when the selected language has a profile, a banner appears above the filtered list ("Selezione curata per il tedesco, N modelli") with a Mostra button and a dismiss (x). Dismiss is in-memory per language (may reappear next session; it is an invitation, not an obligation).
3. **Profile view** (Mostra): escape chip ("← Tutte le lingue"), profile name, optional intro paragraph, then the declared sections in order. Entries REUSE existing cards: bundled entries render today's variant cards (Download/Active), community entries render the catalog cards with one-tap import. The Advanced section stays reachable at the bottom.
4. **Escape**: chip or filter deselection returns to the universal tab; the banner does not re-show while the language stays selected (in-memory shown-once memo).

Free extension: importing a community index that carries profiles for new languages makes those languages badged automatically. No extra UI.

## Error handling

- Malformed profile element: dropped with log; the rest of the index works.
- Unresolvable entry reference: dropped with log; an emptied section disappears; a profile with zero remaining sections is not offered (no badge, no banner).
- Sanity limits enforced at parse (see data model).
- Offline-safe: works from in-memory/bundled data; no new network dependency.
- Any resolver anomaly degrades to "no profiles" = today's tab. Never a broken screen.

## Testing

- Parser: valid profile; malformed element (dropped, logged); limits exceeded (tested behavior); intro absent/present; index without profiles (today's behavior).
- Resolver: official beats third party per language; third-party-only languages added; references resolving bundled vs community vs nothing.
- UI (Robolectric): badge only for profiled languages; banner appears after selection only; Mostra enters profile view; escape chip returns; per-language in-memory dismiss.
- Docs contract: if launch profiles (de, it) are documented by name, a test asserts the bundled index contains them (mirrors the ExternalCatalogTest sync contract).

## Launch content

We curate two official profiles in the bundled index: Deutsch (4 sections: Empfohlen = Whisper v3 Turbo German; Kompakt = NeMo Flash German + Kroko DE; Dialekte = Swiss German; Mehrsprachig = Parakeet + Whisper Turbo) and Italiano (Distil Italian as Empfohlen, Parakeet, Gemma under a section to be decided at implementation). Exact composition is a content decision at implementation time; the mockup's table is the reference start.

## Deliberately out of scope (v1)

Hardware-class profiles (maintainer: low interest), country pages as a first-class axis (a third party may build them on the generic mechanism), profile styling, deep section merging, persisted profile preference, per-section intros, automatic locale-based suggestions.
