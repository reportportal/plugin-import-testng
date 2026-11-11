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

import static com.epam.reportportal.extension.testng.service.FileExtensionConstant.XML_EXTENSION;
import static com.epam.reportportal.extension.testng.service.FileExtensionConstant.ZIP_EXTENSION;

import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.io.FilenameUtils;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Resolves an {@link ImportStrategy} by the uploaded file extension.
 */
public class ImportStrategyFactory {

  private final Map<String, ImportStrategy> strategies;

  public ImportStrategyFactory(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository) {
    strategies = Map.of(
        ZIP_EXTENSION, new ZipImportStrategy(eventPublisher, launchRepository),
        XML_EXTENSION, new XmlImportStrategy(eventPublisher, launchRepository)
    );
  }

  public ImportStrategy getImportStrategy(String filename) {
    String extension = FilenameUtils.getExtension(filename).toLowerCase(Locale.ROOT);
    return Optional.ofNullable(strategies.get(extension))
        .orElseThrow(() -> new ReportPortalException(ErrorType.BAD_REQUEST_ERROR, "Incorrect file extension."));
  }
}
