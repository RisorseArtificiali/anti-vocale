"""TASK-633: the cosmetic recipe-strip patterns, ONE owner.

Both scripts that normalize a recipe's blocks must strip the same inert lines
(the bot-landed upstream master carries them): the version generator
(new-fdroid-version.py) strips them from NEW blocks, and the finalize script's
normalize_recipe strips them from the fork branch before the bot comparison.
Before this module the two lists lived in lockstep copies and a new discovery
updated in one place only, silently breaking the bot-first fallback.
"""

# (description, regex applied per line; keep in sync with NOTHING: add here)
INERT_LINE_PATTERNS = [
    ("sdkmanager 'ndk;rXXc' prebuild line (downloads an NDK nothing consumes)",
     r"^[ \t]*- sdkmanager 'ndk;r\d+[a-z]'\n"),
]

INERT_LITERAL_REPLACES = [
    ("apt list zip removal",
     "wget build-essential cmake g++ zip unzip",
     "wget build-essential cmake g++ unzip"),
]
