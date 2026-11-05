# Spec Delta

## ADDED Requirements

### Requirement: Router listing routes
Core SHALL route `GET /v1/routers` to the router listing and `GET /v1/routers/{id}`, where `{id}` is one segment, to
the single router lookup (see router-deployment). Both are tried before the resource routes.

#### Scenario: Router listing
- **WHEN** an authenticated client sends `GET /v1/routers`
- **THEN** the router listing answers

#### Scenario: Single router
- **WHEN** an authenticated client sends `GET /v1/routers/my%20router`
- **THEN** the router lookup receives id `my router`
