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

import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imports a single {@code testng-results.xml} file.
 */
@Slf4j
public class XmlImportStrategy extends AbstractImportStrategy {

  public XmlImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository) {
    super(eventPublisher, launchRepository);
  }

  @Override
  public String importLaunch(MultipartFile file, String projectName, LaunchImportRQ rq) {
    String launchUuid = null;
    try {
      // checked before the launch is created, so a wrong file doesn't leave an empty launch behind
      verifyTestNgReport(file);
      Instant launchStartTime = getExistingLaunchStartTime(rq);
      launchUuid = getLaunchUuid(getLaunchName(file), projectName, rq);
      ParseResults results;
      try (InputStream xmlStream = file.getInputStream()) {
        results = parseService.parse(xmlStream, launchUuid, projectName, isSkippedNotIssue(rq), launchStartTime);
      }
      completeCreatedLaunch(launchUuid, projectName, ParseResults.merge(List.of(results)), rq);
      return launchUuid;
    } catch (Exception e) {
      log.error("Failed to import TestNG XML file '{}' into launch '{}' for project '{}'",
          file.getOriginalFilename(), launchUuid, projectName, e);
      updateBrokenCreatedLaunch(launchUuid, rq);
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, cleanMessage(e));
    }
  }

  private void verifyTestNgReport(MultipartFile file) throws IOException {
    try (InputStream xmlStream = file.getInputStream()) {
      if (!parseService.isTestNgReport(xmlStream)) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "File is not a TestNG results XML file (root element must be 'testng-results')");
      }
    }
  }
}
