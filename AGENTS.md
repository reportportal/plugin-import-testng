# AGENTS.md

This file provides guidance to AI coding agents (Claude Code, Cursor, Codex, Gemini CLI, etc.) when working with code in this repository.

## What this is

A ReportPortal (pf4j) **import plugin** (`pluginId=testng`) that turns TestNG `testng-results.xml` files (or a zip of them) into a ReportPortal launch. It runs inside ReportPortal `service-api` and compiles against it; it was derived from the ReportPortal plugin template and modelled on the sibling `../plugin-import-junit` project, which is the reference for build/dependency conventions (e.g. the Spring Boot 4 migration was copied from its commit `c1f8e9e`).

## Commands

```bash
./gradlew build                                   # UI bundle + compile + tests + jacoco + jars in build/libs
./gradlew build -x npm_run_build -x npmInstall    # Java only (skip Node/webpack)
./gradlew test -x npm_run_build -x npmInstall --tests '*TestNgParseServiceTest'            # one class
./gradlew test -x npm_run_build -x npmInstall --tests '*TestNgParseServiceTest.shouldMarkSkippedAsNotIssue'  # one method
./gradlew build -PreleaseMode                     # use released com.epam.reportportal:service-api instead of jitpack commit
```

`compileJava` depends on `npm_run_build`, so always exclude the npm tasks when Node deps aren't installed. UI (in `ui/`): `npm install`, `npm run build`, `npm run lint`, `npm run manage:translations`.

## Build / dependency setup

- JDK 25 toolchain; Gradle wrapper 9.8.2.
- `service-api` comes from jitpack (`com.github.reportportal:service-api:<commit>`, currently `d4b2f7c`) unless `-PreleaseMode`. Its annotationProcessor must exclude `spring-modulith-apt`.
- There is **no** `io.spring.dependency-management` plugin: `service-api` brings the Spring Boot 4 BOM, so dependencies (including test deps, Jackson 3 `tools.jackson.core:jackson-databind`) are declared without versions. Spring is a 7.x milestone line.
- Jackson 3: inject `tools.jackson.databind.json.JsonMapper` (not `ObjectMapper`); annotations stay `com.fasterxml.jackson.annotation.*`.
- Plugin metadata (id, class, name, manifest flags such as `isIntegrationsAllowed=false`) lives in `gradle.properties` and is written into the jar manifest by `build.gradle`. Resources under `src/main/resources` and `ui/build` are packaged into `/resources` in the jar (they are excluded from the normal classpath).
- To inspect ReportPortal APIs, `javap` the cached jar under `~/.gradle/caches/modules-2/files-2.1/com.github.reportportal/service-api/<commit>/`.

## Architecture

Package root: `src/main/java/com/epam/reportportal/extension/testng`.

- **Loading**: `TestNgImportPlugin` (no-arg pf4j `Plugin`) + `TestNgImportPluginExtension` (`@Extension`, `ReportPortalExtensionPoint`). ReportPortal's extension factory calls the `Map<String,Object> initParams` constructor (resources dir comes from `IntegrationTypeProperties.RESOURCES_DIRECTORY`) and then autowires fields. The extension reports `IntegrationGroupEnum.IMPORT`, exposes the single common command `import`, and registers `event/PluginLoadedEventHandler`, which on upload creates the one global integration and stores UI binary data from `binary-data.properties`.
- **Command → strategy**: `command/TestNgImportCommand` validates args (`file` multipart, `projectName`, optional `entity` → `model/LaunchImportRQ`) and picks a strategy by extension via `ImportStrategyFactory` (`XmlImportStrategy`, `ZipImportStrategy`). `AbstractImportStrategy` owns the launch lifecycle: start launch with a placeholder `Instant.EPOCH` start, parse, finish launch, then overwrite start time with the earliest parsed time; on failure the created launch is set to `INTERRUPTED`. If `launchUuid` is given, results go into that existing launch and it is neither started nor finished (items earlier than its start are rejected).
- **Parsing**: `TestNgParseService` builds an XXE-hardened SAX parser; `TestNgImportHandler` streams `testng-results.xml` and **publishes Spring events** (`StartRootItemRqEvent`, `StartChildItemRqEvent`, `FinishItemRqEvent`, `SaveLogRqEvent`, plus launch events in strategies) — the plugin never calls services directly. The mapping is defined by the user story `US-LAUN-TNG-001` in `../reportportal-requirements/domains/projects/launches/stories/`: suite→SUITE, test→TEST, `<class>`/`<groups>` produce no items, test-method→STEP; `is-config` methods are BEFORE_METHOD/AFTER_METHOD depending on whether a regular method of the same class was already seen. End times are `started-at + duration-ms` (not `finished-at`); a start earlier than the parent's start is clamped to it, because service-api rejects such children (`CHILD_START_TIME_EARLIER_THAN_PARENT`). A `test-method` is buffered in `model/TestMethodInfo` and reported at its end tag because params/attributes/logs are nested; containers (`model/ItemInfo`) are reported on start. `retried="true"` marks an execution TestNG re-ran: the next execution with the same class/name/params gets `retry=true, retryOf=<previous uuid>` (ReportPortal then shows earlier executions as retries of the last). Logs are spaced 1 µs apart to keep order; the exception is the last ERROR log and its headline is appended to the description. Launch times come from suites only.
- Both strategies check every file's root element with `TestNgParseService.isTestNgReport` (reads only up to the root element) **before** starting a launch, so wrong-format uploads fail without leaving an empty launch. `ZipImportStrategy` imports only entries whose root is `testng-results` and enforces zip-bomb limits (`MAX_ENTRIES`, `MAX_UNCOMPRESSED_BYTES` via `utils/UncompressedSizeLimit`, which counts bytes actually read); a package-private constructor lets tests lower them.
- `utils/DateUtils` handles the several `started-at` formats different TestNG versions emit.

`src/test/resources/testng-results.xml` is the single sample report: a real TestNG report attached to the Jira ticket (MSK timestamps, retries, config methods, data providers). `TestNgSampleReportTest` imports it end to end and is the regression test for the mapping; the strategy tests also use it. `TestNgParseServiceTest` covers edge cases the sample doesn't contain (attributes, description + error, zero-duration log order, invalid times, XXE) with small inline XML — add new edge cases there rather than new resource files. Strategy tests use a list-collecting lambda as the `ApplicationEventPublisher` and mock `LaunchRepository`.

## UI

`ui/` is a webpack Module Federation bundle (scope `plugin_name` must equal webpack `name`). It currently exposes no extensions (`src/metadata.json` `extensions: []`) because the import dialog is rendered by the ReportPortal host UI; `ui/README.md` (still template text) documents how to add extensions, localization and dev overrides.

## Versioning

Versions outside `main` are not release versions: `NEXT_RELEASE_VERSION-SNAPSHOT-NUMBER_OF_BUILD` (e.g. `1.0.1-SNAPSHOT-1`).
