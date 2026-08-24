# ATX Plan Android contributor rules

## Product language

- English is the canonical and only product language until the owner explicitly changes this rule.
- Write all user-visible text, accessibility descriptions, validation errors, demo content, screenshots, tests, documentation, commit messages, and release metadata in English.
- Proper nouns and official names such as São Paulo, Anatel, IBGE, and ITU-R keep their established spelling.
- Do not add Portuguese fallback strings or partially translated screens.

## Engineering integrity

- Clearly distinguish delivered, foundation, planned, and blocked capabilities.
- Never present an unimplemented RF/GIS model or missing dataset as an engineering result.
- Keep units, dataset provenance, numerical assumptions, and `NoData` states explicit.
- Treat imported projects and datasets as untrusted input.

## Repository boundary

This is an independent Android repository. Before staging, committing, changing remotes, or pushing, verify that `git rev-parse --show-toplevel` resolves to this Android project directory and never to an enclosing desktop repository.
