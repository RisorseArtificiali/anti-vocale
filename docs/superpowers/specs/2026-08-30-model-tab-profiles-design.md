# Model Tab Curated Profiles: Design

Date: 2026-08-30. Status: approved design, pending backlog decomposition (no implementation scheduled). Origin: GH #60 seed (maintainer's idea, not derei's proposal: his static device-tier taxonomy was rejected; the dynamic-catalog infrastructure made a data-driven alternative possible). Brainstorm session with visual companion, mockups in `.superpowers/brainstorm/4190502-1788087683/` (gitignored). Revised after spec review (4 blocking, 6 minor findings addressed).

## Problem

The Model tab presents one static, universally identical organization. The primary slots reflect an Italian-market tilt (Distil Italian's badge, Parakeet's recommended default); a German user's strongest options (German Whisper fine-tune, NeMo Flash de, Kroko DE) are buried in the Advanced import flow. Meanwhile the project ships two catalog sources: the bundled static catalog and the runtime-fetchable, user-overridable community index (TASK-401). The declared reality is the variance/complexity matrix (device x backend x model x language, README "How this project is built"): any curation must be data-driven and evolvable, not a hardcoded device or country taxonomy.

## Decision summary

- **Profiles** are named, curated organizations of the Model tab: sections in a declared order, each referencing models (bundled or community) with an optional badge and an optional intro paragraph.
- The mechanism is **generic** (any criterion a catalog author wants); WE publish **language profiles** in the official index. Third-party indexes may carry their own (country, hardware class, anything).
- The **language filter stays the primary tool for everyone**, including minority languages that will never have a page. Profiles surface only as an elevation (option D1: badge in the dropdown + one-tap banner after the language is selected). Never automatic.
- Profiles live **inside the existing index** (`app/src/main/assets/external-catalog/index.json` bundled; the fetched community index at the same shape), so importing a catalog that carries profiles changes what the tab can offer. No new fetch infrastructure: loading reuses `ExternalCatalogRepository`.

## Data model

Additive key `"profiles"` in the index:

```json
"profiles": [
  {
    "id": "de",
    "name": "Deutsch, kuratiert",
    "language": "de",
    "intro": "Für Deutsch optimierte Auswahl: ...",
    "sections": [
      { "title": "Empfohlen", "entries": ["Whisper v3 Turbo German int8 (sherpa, primeline fine-tune)"], "badge": "Empfohlen für Deutsch" },
      { "title": "Kompakt",   "entries": ["NeMo Flash 180M (German)", "Kroko Community Zipformer German (streaming, CC-BY-SA 4.0, Banafo / kroko.ai)"] },
      { "title": "Dialekte",  "entries": ["Whisper v3 Turbo Swiss German int8 (sherpa, Flurin17 fine-tune)"] },
      { "title": "Mehrsprachig", "entries": ["sherpa-onnx", "whisper"] }
    ]
  }
]
```

Reference rules:
- An `entries` string matches EITHER a community entry's `name` (exact, case-sensitive string equality against the index's own entries; community entries have no id field today) OR a bundled catalog entry id (`sherpa-onnx` = Parakeet, `whisper`, `qwen3-asr`, `gigaam`, `nemotron-streaming`). Exact match only, no substring or fuzzy resolution.
- Reference granularity in v1 is the ENTRY, not the variant: referencing `whisper` surfaces the whole Whisper card (all its variants); per-variant selection or ordering inside a card is out of scope for v1 (the Distil Italian variant keeps living inside the Whisper card, where its badge already is).
- `language`: single ISO code. Reachability constraint: the tab's language dropdown lists `Language.FILTER_ENTRIES` (~52 codes); a profile whose `language` is not in that set can exist in an index but can never be offered through the selector (e.g. `gsw` today). State this in the authoring docs.
- `intro`: optional plain string, rendered under the profile title. v1 is a plain string; a locale map can extend it later without breaking the format.
- No styling/layout fields in v1 beyond section title + entries + one optional badge per section.
- An index without `profiles` behaves exactly as today. Compatibility basis (an invariant to preserve, not an accident): `ExternalCatalog.parseIndex` is unknown-key-lenient and must REMAIN so; old clients ignore the key forever.

Sanity limits (parse time): max 20 sections per profile, 50 entries per section, 500 chars for name/intro/titles/badge.

## Resolution (ProfileResolver)

New `data/catalog/ProfileResolver`. Sources, with priority per `language`:

1. **Fetched official index** (when the catalog URL is the default, i.e. no override): the freshest official curation, wins for its languages. This ordering exists so official profile updates published between app releases DO reach users; the bundled asset must never shadow them.
2. **Bundled official asset** (always available, offline-safe snapshot).
3. **Third-party override index** (when the user has overridden the catalog URL): adds languages the official set does not cover; for languages the official side also curates, the official data (fetched or asset) wins.

Duplicate profile ids across sources: same priority rule. No deep section merging in v1.

Loading model: profile availability is asynchronous state, not a synchronous call. The Model tab gains a load trigger that reuses `ExternalCatalogRepository` (which fetches/caches the active index and falls back to the bundled asset offline); while the fetch is in flight the resolver offers bundled-asset profiles only, and the UI renders badges from whatever is currently available. A fetch CAN happen on tab entry when the URL source is active; this is existing repository behavior, not new network code.

## UI behavior (option D1)

State: the tab's existing ephemeral `filterLanguageCode` plus a new `activeProfile: CatalogProfile?` (null = universal tab). No persisted preference; profile is a view state.

1. **Dropdown** (`LanguageFilterBar`, the endonym ExposedDropdownMenuBox the tab already uses): languages with an available profile carry a star badge next to the endonym. Selecting any language (badged or not) filters the list exactly as today.
2. **Banner**: when the selected language has a profile, a banner appears above the filtered list ("Selezione curata per il tedesco, N modelli") with a Mostra button and a dismiss (x). Dismiss memo is per language, held in tab-level `remember` state: it survives recomposition but NOT tab re-entry or process death, and the banner may legitimately reappear then (it is an invitation, not an obligation).
3. **Profile view** (Mostra): escape chip ("← Tutte le lingue"), profile name, optional intro paragraph, then the declared sections in order. The profile view does NOT re-apply the language filter over its sections; it shows what the author declared. Entries REUSE existing cards: bundled entries render today's variant cards (Download/Active), community entries render the catalog cards with one-tap import. The Advanced section stays reachable at the bottom.
4. **Escape**: chip or filter deselection returns to the universal tab; the banner does not re-show while the language stays selected (the dismiss/shown-once memo).

Free extension: importing (overriding to) a community index that carries profiles for new languages makes those languages badged automatically, within the FILTER_ENTRIES reachability constraint above.

## Error handling

- Malformed profile element: dropped with log; the rest of the index works.
- Unresolvable entry reference (no exact match): dropped with log; an emptied section disappears; a profile with zero remaining sections is not offered (no badge, no banner).
- Sanity limits enforced at parse (see data model).
- Offline: bundled-asset profiles always available; fetched profiles subject to the existing repository cache.
- Any resolver anomaly degrades to "no profiles" = today's tab. Never a broken screen.

## Testing

- Parser: valid profile; malformed element (dropped, logged); limits exceeded (tested behavior); intro absent/present; index without profiles (today's behavior); unknown-key leniency preserved.
- Resolver: fetched-official beats bundled asset for the same language (the freshness rule); bundled asset beats third-party override; third-party-only languages added; references resolving bundled vs community (exact name) vs nothing; profile with language outside FILTER_ENTRIES offered=false.
- UI (Robolectric): badge only for profiled languages; banner appears after selection only; Mostra enters profile view; escape chip returns; per-language dismiss memo semantics (tab-level remember).
- Docs contract: a test asserts the bundled index contains the documented launch profiles by name (Deutsch, Italiano), mirroring the ExternalCatalogTest sync contract.

## Launch content

We curate two official profiles (id/name per the worked example above; the example's `entries` strings are the REAL current index names and bundled ids):

- **Deutsch**: Empfohlen = German Whisper (community); Kompakt = NeMo Flash German + Kroko DE (community); Dialekte = Swiss German (community); Mehrsprachig = Parakeet (`sherpa-onnx`) + Whisper (`whisper`).
- **Italiano**: Empfohlen = `whisper` (the Distil Italian variant lives in that card with its badge); Mehrsprachig = `sherpa-onnx`. Gemma is OUT of reach for v1 references (it has no bundled catalog id; it renders through `ModelDownloader.ModelVariant`, a pipeline catalog profiles do not touch) and is excluded from launch content; a future `gemma:<variant>` reference kind is a possible extension, not v1.

## Deliberately out of scope (v1)

Hardware-class profiles (maintainer: low interest), country pages as a first-class axis (a third party may build them on the generic mechanism), profile styling, deep section merging, persisted profile preference, per-section intros, per-variant curation inside a card, automatic locale-based suggestions, a `gemma:` reference kind.
