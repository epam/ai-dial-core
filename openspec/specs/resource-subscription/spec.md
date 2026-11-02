# resource-subscription Specification

## Purpose
TBD - created by archiving change subscribe-complex-resources. Update Purpose after archive.

## Requirements

### Requirement: Subscribing to complex resources
The subscribe API SHALL accept the URL of a SKILL or PROJECT resource the caller can read, and SHALL reject a folder
URL with 400.

#### Scenario: Skill URL is accepted
- **WHEN** a caller with read access subscribes to `skills/{bucket}/{name}`
- **THEN** the response is 200 with `text/event-stream`

#### Scenario: Folder URL is rejected
- **WHEN** a caller subscribes to `skills/{bucket}/folder/`
- **THEN** the response is 400 `resource folder is not supported`

### Requirement: Events of a complex resource
Core SHALL publish one event per created, updated or deleted resource, with the resource URL, and one event per
created, changed or removed file, with the file URL `{resource URL}/files/{path}`. Core SHALL NOT publish events for
the marker or for version files.

#### Scenario: Resource and file lifecycle
- **WHEN** a skill is created, a file is added, changed and deleted, and the skill is deleted
- **THEN** the subscriber receives CREATE, UPDATE and DELETE events for the skill and for each file in that order,
  and no event whose URL contains `.dial-resource` or `/v/`

### Requirement: Resource subscription covers its files
A subscription to a resource URL SHALL deliver the events of that resource and of the files inside it, and SHALL NOT
deliver events of other resources.

#### Scenario: Other resources are not delivered
- **WHEN** a subscriber of `skills/{bucket}/other-skill` and another skill is created
- **THEN** the subscriber receives nothing for the other skill
