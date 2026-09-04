#!/usr/bin/env python3
"""Promote the CHANGELOG.md "Unreleased" section into a released version section.

Rewrites CHANGELOG.md in place:
  - "## [Unreleased]" becomes "## [VERSION] - YYYY-MM-DD", with the HAPI FHIR
    version this release builds against recorded underneath it
  - a fresh, empty "## [Unreleased]" section is opened above it
  - the link-reference block at the bottom gains a line for the new version and
    its "Unreleased" line is re-pointed at the new tag

Also writes release-notes-changelog.md containing just the body of the section
that was promoted, for use in the GitHub release notes.

Environment:
  VERSION       release version being cut, e.g. "2.0.0"       (required)
  HAPI_VERSION  HAPI FHIR version from pom.xml, e.g. "8.4.0"  (required)
  REPO          "owner/name", defaults to the openFHIR repo
  CHANGELOG     path to the changelog, defaults to CHANGELOG.md
"""

import datetime
import os
import re
import sys

VERSION = os.environ["VERSION"]
HAPI_VERSION = os.environ["HAPI_VERSION"]
REPO = os.environ.get("REPO", "openFHIR/openfhir-hapi-interceptor")
PATH = os.environ.get("CHANGELOG", "CHANGELOG.md")
NOTES_PATH = os.environ.get("NOTES", "release-notes-changelog.md")

text = open(PATH, encoding="utf-8").read()

if re.search(rf"^## \[{re.escape(VERSION)}\]", text, re.MULTILINE):
    sys.exit(f"CHANGELOG.md already has a section for {VERSION}")

heading_re = re.compile(r"^## \[Unreleased\].*$", re.MULTILINE)
match = heading_re.search(text)
if not match:
    sys.exit("CHANGELOG.md has no '## [Unreleased]' heading to promote")

# The Unreleased body runs from the end of its heading to the next "## " heading
# (or to the link-reference block / end of file, whichever comes first).
body_start = match.end()
next_heading = re.compile(r"^## ", re.MULTILINE).search(text, body_start)
link_block = re.compile(r"^\[Unreleased\]:", re.MULTILINE).search(text, body_start)
ends = [m.start() for m in (next_heading, link_block) if m]
body_end = min(ends) if ends else len(text)

body = text[body_start:body_end].strip("\n")
if not body.strip():
    sys.exit(
        "The CHANGELOG.md 'Unreleased' section is empty — "
        "add entries describing this release before tagging it."
    )

# The most recently released version, read before any rewriting, so the new
# version's compare link points at the right predecessor.
prev_match = re.search(r"^## \[(?!Unreleased\])([^\]]+)\]", text, re.MULTILINE)
prev_version = prev_match.group(1) if prev_match else None

date = datetime.date.today().isoformat()
version_block = (
    f"## [{VERSION}] - {date}\n"
    f"\n"
    f"- **HAPI FHIR:** {HAPI_VERSION}\n"
    f"\n"
    f"{body}\n"
    f"\n"
)

text = text[:match.start()] + "## [Unreleased]\n\n" + version_block + text[body_end:]

# Refresh the link-reference block at the bottom so both the new version and
# "Unreleased" point at the right compare ranges.

if "[Unreleased]:" in text:
    text = re.sub(
        r"^\[Unreleased\]: .*$",
        f"[Unreleased]: https://github.com/{REPO}/compare/{VERSION}...HEAD",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if prev_version:
        new_link = f"[{VERSION}]: https://github.com/{REPO}/compare/{prev_version}...{VERSION}"
    else:
        new_link = f"[{VERSION}]: https://github.com/{REPO}/releases/tag/{VERSION}"
    text = re.sub(
        r"^(\[Unreleased\]: .*)$",
        lambda m: m.group(1) + "\n" + new_link,
        text,
        count=1,
        flags=re.MULTILINE,
    )

with open(PATH, "w", encoding="utf-8") as fh:
    fh.write(text)

with open(NOTES_PATH, "w", encoding="utf-8") as fh:
    fh.write(body + "\n")

print(f"Promoted 'Unreleased' to {VERSION} (HAPI FHIR {HAPI_VERSION})")
