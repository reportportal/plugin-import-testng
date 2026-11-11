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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.reportportal.base.infrastructure.events.FinishLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartRootItemRqEvent;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.launch.Launch;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ZipImportStrategyTest {

  private final List<Object> events = new ArrayList<>();
  private final LaunchRepository launchRepository = mock(LaunchRepository.class);
  private final Launch launch = new Launch();
  private final ZipImportStrategy strategy = new ZipImportStrategy(events::add, launchRepository);

  @Test
  void shouldImportTestNgResultsAndSkipOtherXmlFiles() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(launch));
    MockMultipartFile file = zip("results.zip", "surefire/testng-results.xml", "surefire/TEST-LoginTest.xml",
        "__MACOSX/surefire/._testng-results.xml", ".DS_Store", "screenshots/login.png");

    String launchUuid = strategy.importLaunch(file, "project", new LaunchImportRQ());

    StartLaunchRqEvent startLaunch = eventsOf(StartLaunchRqEvent.class).get(0);
    assertEquals(launchUuid, startLaunch.getStartLaunchRq().getUuid());
    assertEquals("results", startLaunch.getStartLaunchRq().getName());
    assertEquals(6, eventsOf(StartRootItemRqEvent.class).size());
    assertEquals(Instant.parse("2025-05-14T14:06:51.087Z"),
        eventsOf(FinishLaunchRqEvent.class).get(0).getFinishExecutionRq().getEndTime());
    assertEquals(Instant.parse("2025-05-14T14:06:39Z"), launch.getStartTime());
  }

  @Test
  void shouldCombineMultipleTestNgFilesIntoOneLaunch() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(launch));
    MockMultipartFile file = zip("results.zip", "a/testng-results.xml", "b/testng-results.xml");

    strategy.importLaunch(file, "project", new LaunchImportRQ());

    assertEquals(1, eventsOf(StartLaunchRqEvent.class).size());
    assertEquals(12, eventsOf(StartRootItemRqEvent.class).size());
    assertEquals(1, eventsOf(FinishLaunchRqEvent.class).size());
  }

  @Test
  void shouldFailWithoutCreatingLaunchWhenArchiveHasNoTestNgResults() throws IOException {
    MockMultipartFile file = zip("junit.zip", "TEST-LoginTest.xml", "__MACOSX/._testng-results.xml");

    assertThrows(ReportPortalException.class, () -> strategy.importLaunch(file, "project", null));
    assertTrue(events.isEmpty());
    verifyNoInteractions(launchRepository);
  }

  @Test
  void shouldRejectArchiveWithTooManyEntriesBeforeCreatingLaunch() throws IOException {
    ZipImportStrategy limited = new ZipImportStrategy(events::add, launchRepository, 2, Long.MAX_VALUE);
    MockMultipartFile file = zip("many.zip", "a/testng-results.xml", "b/testng-results.xml", "c/testng-results.xml");

    ReportPortalException e = assertThrows(ReportPortalException.class,
        () -> limited.importLaunch(file, "project", null));
    assertTrue(e.getMessage().contains("more than 2 entries"));
    assertTrue(events.isEmpty());
  }

  @Test
  void shouldRejectArchiveExceedingUncompressedSizeLimit() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(launch));
    // a single report fits, two of them don't
    ZipImportStrategy limited = new ZipImportStrategy(events::add, launchRepository, 100, 200 * 1024);
    MockMultipartFile file = zip("big.zip", "a/testng-results.xml", "b/testng-results.xml");

    ReportPortalException e = assertThrows(ReportPortalException.class,
        () -> limited.importLaunch(file, "project", null));
    assertTrue(e.getMessage().contains("exceeds"));
  }

  private static MockMultipartFile zip(String name, String... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String entry : entries) {
        zip.putNextEntry(new ZipEntry(entry));
        if (entry.endsWith(".xml") && !entry.contains("/._")) {
          String resource = entry.endsWith("testng-results.xml") ? "testng-results.xml" : "wrong-format-junit-report.xml";
          try (InputStream in = ZipImportStrategyTest.class.getClassLoader().getResourceAsStream(resource)) {
            in.transferTo(zip);
          }
        } else {
          // binary junk that would fail XML parsing if it was processed
          zip.write(new byte[] {0, 5, 22, 7, 0, 2, 0, 0});
        }
        zip.closeEntry();
      }
    }
    return new MockMultipartFile("file", name, "application/zip", bytes.toByteArray());
  }

  private <T> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
