/*
 * Copyright 2026 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.reportportal.extension.testng.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.reportportal.base.infrastructure.events.FinishItemRqEvent;
import com.epam.reportportal.base.infrastructure.events.SaveLogRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartChildItemRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartRootItemRqEvent;
import com.epam.reportportal.base.reporting.FinishTestItemRQ;
import com.epam.reportportal.base.reporting.ParameterResource;
import com.epam.reportportal.base.reporting.SaveLogRQ;
import com.epam.reportportal.base.reporting.StartTestItemRQ;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Imports {@code testng-results.xml} – a real report produced by the ReportPortal TestNG examples, attached to the
 * TestNG import plugin Jira ticket (US-LAUN-TNG-001). Times in it are written as {@code yyyy-MM-dd'T'HH:mm:ss MSK}.
 */
class TestNgSampleReportTest {

  private static final String LAUNCH_UUID = "launch-uuid";

  private final List<Object> events = new ArrayList<>();
  private ParseResults results;

  @BeforeEach
  void importReport() throws Exception {
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("testng-results.xml")) {
      results = new TestNgParseService(events::add).parse(stream, LAUNCH_UUID, "project", true, null);
    }
  }

  @Test
  void shouldUseEarliestSuiteStartAndLatestSuiteEndAsLaunchTimes() {
    // "Inner Logging Tests" started at 17:06:39 MSK; "Custom Annotations Test" ended at 17:06:51 MSK + 87 ms
    assertEquals(Instant.parse("2025-05-14T14:06:39Z"), results.getStartTime());
    assertEquals(Instant.parse("2025-05-14T14:06:51.087Z"), results.getEndTime());
  }

  @Test
  void shouldCreateFlatSuiteTestStepHierarchy() {
    List<StartTestItemRQ> suites = eventsOf(StartRootItemRqEvent.class).stream()
        .map(StartRootItemRqEvent::getStartTestItemRq).toList();
    assertEquals(6, suites.size());
    suites.forEach(it -> assertEquals("SUITE", it.getType()));
    assertEquals(Instant.parse("2025-05-14T14:06:44Z"), suites.get(0).getStartTime());

    Map<String, Long> types = childItems().stream()
        .collect(Collectors.groupingBy(StartTestItemRQ::getType, Collectors.counting()));
    List<String> testUuids = eventsOf(StartChildItemRqEvent.class).stream()
        .filter(it -> "TEST".equals(it.getStartTestItemRq().getType()))
        .map(it -> it.getStartTestItemRq().getUuid()).toList();
    eventsOf(StartChildItemRqEvent.class).stream()
        .filter(it -> !"TEST".equals(it.getStartTestItemRq().getType()))
        .forEach(it -> assertTrue(testUuids.contains(it.getParentUuid())));

    // 19 <test>s, 75 regular methods (incl. 21 executions of the retried one), 10 configuration methods; no classes
    assertEquals(Map.of("TEST", 19L, "STEP", 75L, "BEFORE_METHOD", 6L, "AFTER_METHOD", 4L), types);
  }

  @Test
  void shouldTypeConfigurationMethodsByPositionInClass() {
    Map<String, List<String>> typesByName = childItems().stream()
        .filter(it -> it.getType().endsWith("_METHOD"))
        .collect(Collectors.groupingBy(StartTestItemRQ::getName,
            Collectors.mapping(StartTestItemRQ::getType, Collectors.toList())));

    assertEquals(List.of("BEFORE_METHOD", "BEFORE_METHOD", "BEFORE_METHOD", "BEFORE_METHOD"),
        typesByName.get("setupRestAssured"));
    assertEquals(List.of("BEFORE_METHOD", "BEFORE_METHOD"), typesByName.get("startWireMock"));
    assertEquals(List.of("AFTER_METHOD", "AFTER_METHOD"), typesByName.get("stopWireMock"));
    assertEquals(List.of("AFTER_METHOD"), typesByName.get("after"));
    assertEquals(List.of("AFTER_METHOD"), typesByName.get("logLaunchUrl"));
  }

  @Test
  void shouldMapStatusesAndMarkSkippedAsNotIssue() {
    Map<String, Long> statuses = finishes().values().stream()
        .filter(it -> it.getStatus() != null)
        .collect(Collectors.groupingBy(FinishTestItemRQ::getStatus, Collectors.counting()));
    assertEquals(Map.of("PASSED", 59L, "SKIPPED", 20L, "FAILED", 6L), statuses);

    finishes().values().stream()
        .filter(it -> "SKIPPED".equals(it.getStatus()))
        .forEach(it -> assertEquals("NOT_ISSUE", it.getIssue().getIssueType()));
  }

  @Test
  void shouldChainExecutionsOfRetriedMethod() {
    List<StartTestItemRQ> executions = childItems().stream().filter(it -> "failOne".equals(it.getName())).toList();
    assertEquals(21, executions.size());
    assertNull(executions.get(0).getRetry());
    for (int i = 1; i < executions.size(); i++) {
      assertEquals(Boolean.TRUE, executions.get(i).getRetry());
      assertEquals(executions.get(i - 1).getUuid(), executions.get(i).getRetryOf());
    }
    // the last execution passed
    assertEquals("PASSED", finishes().get(executions.get(20).getUuid()).getStatus());
  }

  @Test
  void shouldNeverStartChildBeforeItsParent() {
    Map<String, Instant> startTimes = new HashMap<>();
    eventsOf(StartRootItemRqEvent.class)
        .forEach(it -> startTimes.put(it.getStartTestItemRq().getUuid(), it.getStartTestItemRq().getStartTime()));
    for (StartChildItemRqEvent event : eventsOf(StartChildItemRqEvent.class)) {
      StartTestItemRQ rq = event.getStartTestItemRq();
      startTimes.put(rq.getUuid(), rq.getStartTime());
      assertFalse(rq.getStartTime().isBefore(startTimes.get(event.getParentUuid())),
          () -> rq.getName() + " starts before its parent");
    }
    // a class-level config method reported at 17:06:39 under a <test> started at 17:06:44 is moved to the test start
    StartTestItemRQ earlyConfig = childItems().stream()
        .filter(it -> "setupRestAssured".equals(it.getName()))
        .reduce((first, second) -> second).orElseThrow();
    assertEquals(Instant.parse("2025-05-14T14:06:44Z"), earlyConfig.getStartTime());

    finishes().forEach((uuid, finish) -> assertFalse(finish.getEndTime().isBefore(startTimes.get(uuid))));
  }

  @Test
  void shouldMapStepNameCodeRefAndTiming() {
    StartTestItemRQ step = childItems().stream()
        .filter(it -> "orderProductsTest".equals(it.getName())).findFirst().orElseThrow();
    assertEquals("STEP", step.getType());
    assertEquals("com.epam.reportportal.example.testng.logback.step.NestedStepsTest.orderProductsTest",
        step.getCodeRef());
    assertEquals(Instant.parse("2025-05-14T14:06:44Z"), step.getStartTime());
    // started-at + duration-ms (30), not the second-resolution finished-at
    assertEquals(Instant.parse("2025-05-14T14:06:44.030Z"), finishes().get(step.getUuid()).getEndTime());
  }

  @Test
  void shouldMapParametersDescriptionsAndLogs() {
    StartTestItemRQ parametrized = childItems().stream()
        .filter(it -> "parametrizedTestTemplate".equals(it.getName()) && it.getParameters() != null)
        .filter(it -> "TNG.1.2".equals(it.getParameters().get(0).getValue()))
        .findFirst().orElseThrow();
    assertEquals(List.of("TNG.1.2", "19", "20"),
        parametrized.getParameters().stream().map(ParameterResource::getValue).toList());

    StartTestItemRQ described = childItems().stream()
        .filter(it -> "testDescription".equals(it.getName())).findFirst().orElseThrow();
    assertEquals("Description from Test annotation", described.getDescription());

    List<SaveLogRQ> logs = eventsOf(SaveLogRqEvent.class).stream().map(SaveLogRqEvent::getSaveLogRq).toList();
    Map<String, Long> levels = logs.stream().collect(Collectors.groupingBy(SaveLogRQ::getLevel, Collectors.counting()));
    // one reporter line, one exception per failed or retried execution; the root <reporter-output> is not a log
    assertEquals(Map.of("INFO", 1L, "ERROR", 26L), levels);

    StartTestItemRQ failureTest = childItems().stream()
        .filter(it -> "failureTest".equals(it.getName())).findFirst().orElseThrow();
    List<SaveLogRQ> failureLogs = logs.stream().filter(it -> failureTest.getUuid().equals(it.getItemUuid())).toList();
    assertEquals(List.of("INFO", "ERROR"), failureLogs.stream().map(SaveLogRQ::getLevel).toList());
    assertEquals("This test is expected to fail", failureLogs.get(0).getMessage());
    assertTrue(failureLogs.get(1).getMessage().startsWith("java.lang.IllegalStateException: This test is expected to fail"));
    assertTrue(failureLogs.get(1).getMessage().contains("\n"), "full stack trace is logged");
    assertTrue(failureTest.getDescription().contains("This test is expected to fail"));
  }

  private List<StartTestItemRQ> childItems() {
    return eventsOf(StartChildItemRqEvent.class).stream().map(StartChildItemRqEvent::getStartTestItemRq).toList();
  }

  private Map<String, FinishTestItemRQ> finishes() {
    return eventsOf(FinishItemRqEvent.class).stream()
        .collect(Collectors.toMap(FinishItemRqEvent::getItemUuid, FinishItemRqEvent::getFinishTestItemRQ,
            (a, b) -> a, HashMap::new));
  }

  private <T> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
