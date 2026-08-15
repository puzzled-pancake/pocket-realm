# Wiki maintenance instructions

These rules apply to every file in `docs/wiki` and `docs/wiki/images`.

## Audience and voice

- Write for people who do not know programming, Android internals, game server administration, or this repository.
- Use ordinary human language and short explanations.
- Explain a technical term the first time it matters, then link to `Glossary.md` when useful.
- Do not use em dashes. Rewrite the sentence with a full stop, comma, colon, or parentheses.
- Avoid marketing claims. Describe what the current product can demonstrate.
- Do not make an unfinished or experimental feature sound complete.

## Source boundary

- Document the current product from user-visible app behaviour, current UI source, runtime behaviour, tests that describe current behaviour, and the bundled add-on package.
- Do not copy, summarise, cite, or link research reports, studies, source workbooks, audit documents, investigation notes, or research source lists into this wiki.
- Do not treat plans as finished behaviour.
- When code, an installed build, and an old screenshot disagree, the current verified installed build is the preferred product evidence. Record the date and device.

## How future agents should document the project

1. Start from the current screen and the code that performs the visible action.
2. Follow the action through its service, storage, native, and packaged-project boundaries before describing how it works.
3. Separate normal ARM handheld behaviour from x86 validation-only behaviour.
4. Name an outside project only when the current build, source manifest, lockfile, submodule, or vendored provenance proves that it is used.
5. Explain the project's role in ordinary language. Do not paste implementation comments or large lists of internal constants.
6. Describe safety behaviour honestly, including what is private, verified, bounded, recoverable, experimental, or not encrypted.
7. Never restore removed experiments to the wiki just because related code or old assets remain somewhere in the repository.
8. If the product is still changing, state what is present and what still needs physical qualification.
9. Update the detailed architecture pages as well as the visible screen page when a runtime boundary changes.
10. Keep product documentation independent from research material, planning notes, and historical investigations.

## Screenshots

- Capture screenshots from a current clean build on the intended device or emulator.
- Use landscape images for Retroid Pocket 6 pages unless the feature is specifically about portrait behaviour.
- Do not show passwords, tokens, private addresses, personal account names, or unrelated notifications.
- Use example or test accounts. If an existing screenshot contains personal data, replace it before publishing.
- Store wiki images in `docs/wiki/images` with short descriptive lowercase names.
- Add useful alt text and a plain-language explanation below every screenshot.
- Replace outdated screenshots rather than keeping several nearly identical versions.

## What to update after product changes

1. Check whether the change affects Home, Add-ons, Controls, Settings, import, diagnostics, LAN, accounts, or backups.
2. Update the relevant page and any cross-links.
3. Replace screenshots whose visible labels, layout, choices, or state no longer match.
4. Update the checked date in `README.md` and `Screenshot-Gallery.md` when screenshots change.
5. Keep `Overview.md` honest about the current development state.
6. Update `Troubleshooting.md` only with a verified user-facing symptom and recovery path.
7. Add new terms to `Glossary.md` only when a nontechnical reader will meet them in the wiki.

## Validation before finishing a documentation change

- Check every relative Markdown link.
- Check every referenced image exists.
- Search the wiki for the em dash character and remove it.
- Search for references to excluded research material and remove copied or derived content.
- Read the changed page once as a new player. If it assumes repository knowledge, rewrite it.
- Run the repository wiki checker when one exists. Until then, use a local link and image check plus the searches above.
