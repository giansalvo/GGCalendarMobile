"""Resolves the app's own string resources (English and Italian) into regular expressions.

The tests name a string by its resource name, e.g. S("delete"), and the same test passes whatever the language
of the emulator: the pattern matches the English or the Italian text. No text is duplicated in the tests.
"""
import glob
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

_values = {}  # name -> set of texts (all languages)


def _clean(s):
    s = s.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("\\@", "@")
    return s.strip()


def _load():
    if _values:
        return
    for path in glob.glob(os.path.join(ROOT, "**", "src", "main", "res", "values*", "strings.xml"), recursive=True):
        folder = os.path.basename(os.path.dirname(path))
        if folder not in ("values", "values-it"):
            continue
        for el in ET.parse(path).getroot():
            name = el.get("name")
            if el.tag == "string":
                _values.setdefault(name, set()).add(_clean("".join(el.itertext())))
            elif el.tag == "plurals":
                for item in el:
                    _values.setdefault(name, set()).add(_clean("".join(item.itertext())))


def texts(name):
    _load()
    if name not in _values:
        raise KeyError(f"unknown string resource: {name}")
    return sorted(_values[name])


def _pattern(text):
    parts = re.split(r"%(?:\d+\$)?[sdf]", text)
    return ".*".join(re.escape(p) for p in parts)


def S(name, exact=True):
    """Regex matching the string `name` in any language (case-insensitive; placeholders match anything)."""
    body = "|".join(_pattern(t) for t in texts(name))
    return re.compile(f"^(?:{body})$" if exact else f"(?:{body})", re.IGNORECASE | re.DOTALL)


def has(name):
    """Regex matching any text that contains the string `name`."""
    return S(name, exact=False)


def any_of(*patterns):
    """Regex matching any of the given plain words (system strings such as OK or Cancel)."""
    return re.compile("^(?:" + "|".join(re.escape(p) for p in patterns) + ")$", re.IGNORECASE)


OK = any_of("OK", "Ok")
CANCEL = any_of("Cancel", "Annulla")
