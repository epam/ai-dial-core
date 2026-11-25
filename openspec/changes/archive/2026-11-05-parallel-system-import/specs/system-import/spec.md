## ADDED Requirements

### Requirement: Bounded parallel storage calls
Core SHALL run at most `adminSystemImport.parallelism` (4 by default) blob storage calls of a system import at a time,
while it stages the archive, wipes the buckets, copies the staged blobs and deletes the staging. Every delete of the wipe
SHALL finish before the first copy starts. When a call fails, Core SHALL start no further calls, wait for those in
flight, and fail the phase. A `parallelism` below 1 SHALL stop Core from starting.

#### Scenario: At most the configured number of calls at once
- **WHEN** `adminSystemImport.parallelism` is 2 and an archive of 6 blobs is imported over buckets that hold 6 blobs
- **THEN** no more than 2 storage calls of the import run at the same time, and at some point 2 do

#### Scenario: Copy waits for the wipe
- **WHEN** an archive is imported over buckets that hold blobs
- **THEN** no copy starts before the last delete of the wipe has finished

#### Scenario: A failed call stops the phase
- **WHEN** a delete fails during the wipe
- **THEN** no copy starts, the replace fails once the calls in flight have finished, and the client sees a transport error
