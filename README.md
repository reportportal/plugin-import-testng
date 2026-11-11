# TestNG Import Plugin for ReportPortal

Imports TestNG results (`testng-results.xml`, produced by TestNG's default `XMLReporter`) into ReportPortal
as a launch.

## Usage

The plugin registers the common command `import` (plugin id `testng`), which accepts:

| Argument      | Required | Description                                                                                  |
|---------------|----------|----------------------------------------------------------------------------------------------|
| `file`        | yes      | `testng-results.xml`, or a `.zip` archive containing one or more such files (max 32 MB)      |
| `projectName` | yes      | Target project                                                                               |
| `entity`      | no       | Launch parameters: `name`, `description`, `attributes`, `mode`, `startTime`, `launchUuid`   |

The command is invoked by service-api's `POST /v1/plugin/{projectKey}/{pluginName}/import`, which also publishes
`ImportFinishedEvent`.

- A new launch is created by default and named after the file, unless `name` is given.
- With `launchUuid`, results are added to an existing launch instead.
- A system attribute `skippedIsNotIssue=true` marks skipped tests as *Not an issue*.
- In a zip archive, every `.xml` file whose root element is `testng-results` is imported into one combined launch.
  `__MACOSX/` / `.DS_Store` entries, non-XML files (attachments, images) and XML files of other formats (for example,
  JUnit reports written by Surefire next to `testng-results.xml`) are ignored. The import fails if the archive
  doesn't contain any TestNG results. An archive may contain at most 10,000 entries and 512 MB of uncompressed
  data.
- Files are checked before a launch is created: an upload without TestNG results fails without leaving an empty
  launch behind.

## Report mapping

Implements [US-LAUN-TNG-001](https://jiraeu.epam.com/browse/EPMRPP-103377) (TestNG import as launch). The hierarchy
is flattened to **Launch → Suite → Test → (Before / Step / After)**.

| TestNG report                              | ReportPortal                                                              |
|--------------------------------------------|---------------------------------------------------------------------------|
| uploaded file                              | launch, named after the file (without extension) unless `name` is given  |
| `<suite>`                                  | `SUITE`                                                                   |
| `<test>`                                   | `TEST`                                                                    |
| `<class>`, `<groups>`                      | ignored (no item); class name is only used in the step code reference    |
| `<test-method>`                            | `STEP`, code reference `<class>.<method>`                                 |
| `<test-method is-config="true">`           | `BEFORE_METHOD` before the first regular method of its class, `AFTER_METHOD` after it |
| `status` `PASS` / `FAIL` / `SKIP`          | `PASSED` / `FAILED` / `SKIPPED`                                           |
| `description` attribute                    | step description                                                          |
| `<params>`                                 | step parameters (key = parameter index)                                   |
| `retried="true"`                           | the next execution of the same method (class, name, parameters) is reported with `retryOf` it, so earlier executions are shown as its retries |
| `<reporter-output>` / `<line>`             | `INFO` logs, in order (each 1 µs after the previous one)                  |
| `<exception>`                              | last `ERROR` log (message + full stack trace); `exception: message` is also appended to the step description |
| `<attributes>` (`ITestResult` attributes)  | step attributes                                                           |

Times: start = `started-at`, end = `started-at + duration-ms` (`finished-at` has only second resolution and is used
only when `duration-ms` is missing). Launch start/end = earliest suite start / latest suite end. An item never starts
before its parent (ReportPortal rejects that): TestNG writes e.g. a `@BeforeClass` method of a class shared between
`<test>`s with its own earlier time, so such a start is moved to the parent's start.

## Project structure

```
src/main/java/com/epam/reportportal/extension/testng
├── TestNgImportPlugin.java            pf4j plugin entry point
├── TestNgImportPluginExtension.java   extension point: plugin params, IMPORT group, `import` command
├── command/TestNgImportCommand.java   request validation, strategy selection
├── event/PluginLoadedEventHandler.java creates the global integration and binary data on upload
├── model/                             import request and parsing state
├── service/
│   ├── XmlImportStrategy / ZipImportStrategy / AbstractImportStrategy   launch lifecycle
│   ├── TestNgParseService             XXE-safe SAX parser setup
│   └── TestNgImportHandler            testng-results.xml -> reporting events
└── utils/DateUtils.java               TestNG timestamp parsing
```

## Build

Prerequisites: JDK 25 (Gradle toolchains will provision it).

```bash
./gradlew build
```

This builds the UI bundle (`ui/`, see [`ui/README.md`](ui/README.md)), runs the tests and produces the plugin
jar in `build/libs`. Use `-x npm_run_build -x npmInstall` to build only the Java part.

Plugin metadata (id, version, name, ...) lives in `gradle.properties`. Versions outside the _main_ branch are
not release versions and should follow `NEXT_RELEASE_VERSION-SNAPSHOT-NUMBER_OF_BUILD` (for example
`1.0.1-SNAPSHOT-1`).

## Deploy

1. In ReportPortal, open **Administrate → Plugins**.
2. Click **Upload** and select the jar from `build/libs`.
3. The plugin appears in the **Import** group; TestNG becomes available in the launch import dialog.
