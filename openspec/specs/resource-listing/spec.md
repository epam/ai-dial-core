# resource-listing Specification

## Purpose
What the metadata listing APIs (`GET /v2/metadata/...`) report for the resources under a folder. It covers the fields
of listed items that the item's own metadata endpoint also reports.

## Requirements

### Requirement: Timestamps of complex items in metadata listings
On `GET /v2/metadata/{projects|skills}/{bucket}/{folder}/`, recursive or not, each `PROJECT` or `SKILL` item SHALL
carry the `createdAt` and `updatedAt` that `GET /v2/metadata/` of that item returns, including right after a write to
the resource.

#### Scenario: Listing after a write
- **WHEN** `proxyKey1` creates a project (or a skill) under `listed/`, writes a file into it with `PUT .../files/{path}`, and lists `GET /v2/metadata/projects/<b1>/listed` (or `skills`)
- **THEN** the item has the same `createdAt` and `updatedAt` as `GET /v2/metadata/` of the project (or skill)
