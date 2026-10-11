---
okf_version: "0.2"
---

# AceTag knowledge bundle

An [OKF v0.2](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle: markdown concepts with YAML frontmatter, cross-linked, readable with `cat` and shippable
with `git clone`.

* [Domain](domain/) - what a spool is, how one gets in, and why tags move
* [Format](format/) - the byte-level tag format and the SKU table it draws from
* [Decisions](decisions/) - roads not taken, and why

# Conventions

* Every concept file carries `type` in frontmatter. That is the only required field.
* Links between concepts are bundle-relative and begin with `/`, e.g. `/format/tag-format.md`.
* `status: draft` means the concept is a stub — the real content still lives where the link says.
* `human:` actors in `generated.by` / `verified` mean a person wrote or confirmed it; anything
  else is agent- or process-authored. See §7 of the spec.
* [log.md](log.md) records changes to the bundle.

# Relationship to CLAUDE.md and README.md

Those two files stay authoritative for now. `README.md` is what a user reads; `CLAUDE.md` is the
working brief. This bundle is the long-form knowledge behind both, and the stubs below deliberately
point back rather than copying — duplicated domain rules drift, and the drift is silent.
