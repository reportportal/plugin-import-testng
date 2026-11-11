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
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.launch.Launch;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class XmlImportStrategyTest {

  private final List<Object> events = new ArrayList<>();
  private final LaunchRepository launchRepository = mock(LaunchRepository.class);
  private final XmlImportStrategy strategy = new XmlImportStrategy(events::add, launchRepository);

  @Test
  void shouldImportTestNgResults() throws IOException {
    when(launchRepository.findByUuid(anyString())).thenReturn(Optional.of(new Launch()));

    String launchUuid = strategy.importLaunch(file("testng-results.xml"), "project", new LaunchImportRQ());

    assertEquals(launchUuid, eventsOf(StartLaunchRqEvent.class).get(0).getStartLaunchRq().getUuid());
    assertEquals(1, eventsOf(FinishLaunchRqEvent.class).size());
  }

  @Test
  void shouldFailWithoutCreatingLaunchForNonTestNgXml() throws IOException {
    MockMultipartFile file = file("wrong-format-junit-report.xml");

    ReportPortalException e = assertThrows(ReportPortalException.class,
        () -> strategy.importLaunch(file, "project", new LaunchImportRQ()));
    assertTrue(e.getMessage().contains("not a TestNG results XML file"));
    assertTrue(events.isEmpty());
    verifyNoInteractions(launchRepository);
  }

  @Test
  void shouldFailWithoutCreatingLaunchForMalformedXml() {
    MockMultipartFile file = new MockMultipartFile("file", "broken.xml", "text/xml", "not xml".getBytes());

    assertThrows(ReportPortalException.class, () -> strategy.importLaunch(file, "project", null));
    assertTrue(events.isEmpty());
  }

  private static MockMultipartFile file(String resource) throws IOException {
    try (InputStream in = XmlImportStrategyTest.class.getClassLoader().getResourceAsStream(resource)) {
      return new MockMultipartFile("file", resource, "text/xml", in.readAllBytes());
    }
  }

  private <T> List<T> eventsOf(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
