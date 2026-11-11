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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.epam.reportportal.base.infrastructure.events.FinishItemRqEvent;
import com.epam.reportportal.base.infrastructure.events.SaveLogRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartChildItemRqEvent;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.base.reporting.FinishTestItemRQ;
import com.epam.reportportal.base.reporting.ItemAttributesRQ;
import com.epam.reportportal.base.reporting.SaveLogRQ;
import com.epam.reportportal.base.reporting.StartTestItemRQ;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Edge cases on small inline reports. The mapping of a real report is covered by {@link TestNgSampleReportTest}.
 */
class TestNgParseServiceTest {

  private static final String LAUNCH_UUID = "launch-uuid";
  private static final String PROJECT = "test_project";

  private final List<Object> events = new ArrayList<>();
  private final TestNgParseService parseService = new TestNgParseService(events::add);

  @Test
  void shouldMapTestResultAttributes() throws Exception {
    parse(report("""
        <test-method name="m" status="PASS" started-at="2024-03-01T10:00:00Z" duration-ms="1">
          <attributes>
            <attribute name="browser"><![CDATA[chrome]]></attribute>
            <attribute name="smoke"></attribute>
          </attributes>
        </test-method>"""), false);

    assertEquals(List.of(new ItemAttributesRQ("browser", "chrome"), new ItemAttributesRQ("smoke")),
        List.copyOf(method("m").getAttributes()));
  }

  @Test
  void shouldAppendErrorToExistingDescription() throws Exception {
    parse(report("""
        <test-method name="m" description="Wrong password is rejected" status="FAIL"
            started-at="2024-03-01T10:00:00Z" duration-ms="1">
          <exception class="java.lang.AssertionError">
            <message><![CDATA[expected [true] but found [false]]]></message>
          </exception>
        </test-method>"""), false);

    assertEquals("Wrong password is rejected\n\n**Error:**\n```\n"
        + "java.lang.AssertionError: expected [true] but found [false]\n```", method("m").getDescription());
  }

  @Test
  void shouldKeepSkippedToInvestigateWhenSkippedIsAnIssue() throws Exception {
    parse(report("""
        <test-method name="m" status="SKIP" started-at="2024-03-01T10:00:00Z" duration-ms="0"/>"""), false);

    FinishTestItemRQ finish = eventsOf(FinishItemRqEvent.class).stream()
        .filter(it -> method("m").getUuid().equals(it.getItemUuid()))
        .findFirst().orElseThrow().getFinishTestItemRQ();
    assertEquals("SKIPPED", finish.getStatus());
    assertNull(finish.getIssue());
  }

  @Test
  void shouldKeepLogOrderForZeroDurationMethod() throws Exception {
    parse(report("""
        <test-method name="m" status="FAIL" started-at="2024-03-01T10:00:00Z" duration-ms="0">
          <exception class="E"><message>boom</message></exception>
          <reporter-output><line>one</line><line>two</line></reporter-output>
        </test-method>"""), false);

    List<SaveLogRQ> logs = eventsOf(SaveLogRqEvent.class).stream().map(SaveLogRqEvent::getSaveLogRq).toList();
    assertEquals(List.of("one", "two", "E: boom"), logs.stream().map(SaveLogRQ::getMessage).toList());
    assertTrue(logs.get(0).getLogTime().isBefore(logs.get(1).getLogTime()));
    assertTrue(logs.get(1).getLogTime().isBefore(logs.get(2).getLogTime()));
  }

  @Test
  void shouldFallBackToFinishedAtWhenDurationIsInvalid() throws Exception {
    String xml = """
        <testng-results><suite name="s" started-at="2024-03-01T10:00:00Z" duration-ms="99999999999999999999"
            finished-at="2024-03-01T10:00:07Z"><test name="t" started-at="99999999999999999999" duration-ms="1"/>
        </suite></testng-results>
        """;
    ParseResults results = parse(xml, false);

    assertEquals(Instant.parse("2024-03-01T10:00:07Z"), results.getEndTime());
    // unparseable started-at falls back to the parent's time
    assertEquals(Instant.parse("2024-03-01T10:00:00Z"),
        eventsOf(StartChildItemRqEvent.class).get(0).getStartTestItemRq().getStartTime());
  }

  @Test
  void shouldDetectTestNgReportByRootElement() throws Exception {
    try (InputStream testng = resource("testng-results.xml"); InputStream junit = resource("wrong-format-junit-report.xml")) {
      assertTrue(parseService.isTestNgReport(testng));
      assertFalse(parseService.isTestNgReport(junit));
    }
    assertFalse(parseService.isTestNgReport(stream("garbage")));
    assertTrue(events.isEmpty());
  }

  @Test
  void shouldRejectNonTestNgReportWithoutPublishingEvents() {
    assertThrows(NotTestNgReportException.class, () -> {
      try (InputStream junit = resource("wrong-format-junit-report.xml")) {
        parseService.parse(junit, LAUNCH_UUID, PROJECT, false, null);
      }
    });
    assertTrue(events.isEmpty());
  }

  @Test
  void shouldRejectDoctypeDeclarations() {
    String xxe = """
        <?xml version="1.0"?>
        <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <testng-results><suite name="&xxe;"/></testng-results>
        """;
    assertThrows(ReportPortalException.class, () -> parse(xxe, false));
    assertTrue(events.isEmpty());
  }

  @Test
  void shouldRejectItemsEarlierThanExistingLaunch() {
    Instant launchStart = Instant.parse("2030-01-01T00:00:00Z");
    assertThrows(ReportPortalException.class, () -> {
      try (InputStream stream = resource("testng-results.xml")) {
        parseService.parse(stream, LAUNCH_UUID, PROJECT, false, launchStart);
      }
    });
  }

  private static String report(String testMethods) {
    return """
        <testng-results><suite name="s" started-at="2024-03-01T10:00:00Z" duration-ms="10">
        <test name="t" started-at="2024-03-01T10:00:00Z" duration-ms="10"><class name="C">
        %s
        </class></test></suite></testng-results>
        """.formatted(testMethods);
  }

  private ParseResults parse(String xml, boolean skippedIsNotIssue) throws Exception {
    return parseService.parse(stream(xml), LAUNCH_UUID, PROJECT, skippedIsNotIssue, null);
  }

  private StartTestItemRQ method(String name) {
    return eventsOf(StartChildItemRqEvent.class).stream()
        .map(StartChildItemRqEvent::getStartTestItemRq)
        .filter(it -> name.equals(it.getName()))
        .findFirst().orElseThrow();
  }

  private static InputStream stream(String xml) {
    return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
  }

  private static InputStream resource(String name) {
    return TestNgParseServiceTest.class.getClassLoader().getResourceAsStream(name);
  }

  private <T> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
