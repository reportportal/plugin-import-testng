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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ImportStrategyFactoryTest {

  private final ImportStrategyFactory factory = new ImportStrategyFactory(event -> {
  }, mock(LaunchRepository.class));

  @Test
  void shouldResolveUpperCaseExtensionsRegardlessOfDefaultLocale() {
    Locale defaultLocale = Locale.getDefault();
    try {
      // in Turkish "ZIP".toLowerCase() is "zıp" (dotless i)
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertInstanceOf(ZipImportStrategy.class, factory.getImportStrategy("results.ZIP"));
      assertInstanceOf(XmlImportStrategy.class, factory.getImportStrategy("testng-results.XML"));
    } finally {
      Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void shouldRejectUnsupportedExtension() {
    assertThrows(ReportPortalException.class, () -> factory.getImportStrategy("results.json"));
  }
}
