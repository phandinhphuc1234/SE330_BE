"""Small, safe Unicode normalization rules for parsed PDF text.

This module deliberately avoids aggressive normalization such as full NFKC.
PDF text often contains Vietnamese accents, math symbols, currency symbols, and
special punctuation. A broad normalization pass can silently change semantics,
so this rule only fixes common extraction artifacts we explicitly understand.
"""

from __future__ import annotations

import unicodedata


# Common ligature glyphs that PDF extractors may return as one Unicode
# character. They hurt search/chunking because "efﬁcient" is not equal to
# "efficient", so we expand only these known glyphs.
LIGATURE_REPLACEMENTS = {
    "\ufb00": "ff",  # ﬀ
    "\ufb01": "fi",  # ﬁ
    "\ufb02": "fl",  # ﬂ
    "\ufb03": "ffi",  # ﬃ
    "\ufb04": "ffl",  # ﬄ
    "\ufb05": "st",  # ﬅ
    "\ufb06": "st",  # ﬆ
}

# Spaces that look like normal spaces but are not equal to " ". We map them to
# plain spaces now; collapsing repeated spaces is a separate cleaner step.
PDF_SPACE_REPLACEMENTS = {
    "\u00a0": " ",  # non-breaking space
    "\u2007": " ",  # figure space
    "\u202f": " ",  # narrow non-breaking space
}

# Invisible characters that usually come from copy/extraction artifacts. Keeping
# them makes chunk text hard to search and can create confusing tokenization.
INVISIBLE_ARTIFACTS = {
    "\u00ad",  # soft hyphen
    "\u200b",  # zero-width space
    "\u200c",  # zero-width non-joiner
    "\u200d",  # zero-width joiner
    "\ufeff",  # byte-order mark
}

TRANSLATION_TABLE = str.maketrans(
    {
        **LIGATURE_REPLACEMENTS,
        **PDF_SPACE_REPLACEMENTS,
        **{artifact: "" for artifact in INVISIBLE_ARTIFACTS},
    }
)


def normalize_pdf_unicode(text: str) -> str:
    """Normalize common PDF Unicode artifacts without changing text meaning.

    The rule currently does four conservative things:

    1. Expand known Latin ligatures, for example ``ﬁ`` -> ``fi``.
    2. Convert PDF-specific non-breaking spaces to normal spaces.
    3. Remove invisible zero-width artifacts.
    4. Remove binary/control characters, while preserving newlines and tabs.

    Vietnamese accents are intentionally preserved. The final NFC pass only
    composes equivalent Unicode sequences into a stable representation.
    """

    if not text:
        return text

    translated = text.translate(TRANSLATION_TABLE)
    without_controls = "".join(char for char in translated if _is_allowed_character(char))
    return unicodedata.normalize("NFC", without_controls)


def _is_allowed_character(char: str) -> bool:
    """Return whether a character is safe to keep in cleaned text."""

    if char in {"\n", "\t"}:
        return True

    # Remove NULL and other C0/C1 control characters. These can appear when a
    # PDF extractor returns binary-ish fragments from a damaged or odd file.
    return unicodedata.category(char) != "Cc"
