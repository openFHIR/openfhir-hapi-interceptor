# Changelog

All notable changes to the openFHIR HAPI Interceptor are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each release states the **HAPI FHIR version** the interceptor is built against and the
**openFHIR version** it requires. The interceptor is a HAPI plugin, so the HAPI major/minor
line it is compiled against is the one it is supported on.

<!-- Add entries for unreleased work under the "Unreleased" heading below.
     The release workflow renames this heading to the version being released and
     opens a fresh, empty "Unreleased" section in its place. -->

## [Unreleased]

### Added

- The end-to-end integration suite (`tests/test.sh`) now runs in CI on every pull request to
  `main` and is a hard gate on the release workflow — nothing is tagged or published unless it
  passes. Newman results are exported as JUnit XML and, on failure, container logs are captured
  before the stack is torn down.

### Changed

- The openFHIR image used by the integration stack is pinned to a version tag and can be
  overridden with `OPENFHIR_IMAGE_TAG` (e.g. `OPENFHIR_IMAGE_TAG=build ./tests/test.sh`).

## [2.0.0] - 2026-09-04

- **HAPI FHIR:** 8.4.0
- **Requires openFHIR:** >= 3.0.0

### Changed

- **BREAKING:** the interceptor now targets openFHIR >= 3.0.0 and uses the `$tofhir` /
  `$toopenehr` FHIR operations introduced there. Deployments on openFHIR < 3.0.0 must stay
  on the `1.x` line.
- Query and `$summary` behaviour reworked to follow the new openFHIR `toaql` translation flow.
- The `$summary` operation is no longer hard-wired to the International Patient Summary
  openEHR template; the template ID is configurable via `interceptor.ips.template-id`.
- Upgraded the HAPI FHIR dependency line to 8.4.0.

### Added

- Additional IPS sections beyond Allergies and Conditions.
- Unit tests covering the openFHIR client, query filtering and summary generation.

## [1.1.0] - 2026-05-26

- **HAPI FHIR:** 8.2.0
- **Requires openFHIR:** < 3.0.0

### Added

- Generalised facade implementation, no longer IPS specific.

### Changed

- Documentation and logo updates; Apache 2.0 license badge and a link to the
  [openFHIR Firely plugin](https://github.com/openFHIR/openfhir-firely-plugin).

## [1.0.0] - 2026-05-06

- **HAPI FHIR:** 8.2.0
- **Requires openFHIR:** < 3.0.0

### Added

- Initial release: HAPI FHIR interceptor routing clinical data between FHIR clients and an
  openEHR CDR via openFHIR.
- Store flow: FHIR resources matching a configured profile list are converted to openEHR
  Compositions and written straight to the CDR.
- Query flow: matching FHIR searches are translated to AQL, executed against the CDR and
  converted back to FHIR.
- `GET /fhir/Patient/{id}/$summary` operation returning an International Patient Summary
  Bundle assembled from the CDR.

[Unreleased]: https://github.com/openFHIR/openfhir-hapi-interceptor/compare/2.0.0...HEAD
[2.0.0]: https://github.com/openFHIR/openfhir-hapi-interceptor/compare/1.1.0...2.0.0
[1.1.0]: https://github.com/openFHIR/openfhir-hapi-interceptor/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/openFHIR/openfhir-hapi-interceptor/releases/tag/1.0.0
