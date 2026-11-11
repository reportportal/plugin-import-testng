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

import static java.util.Optional.ofNullable;

import com.epam.reportportal.base.infrastructure.events.FinishLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartLaunchRqEvent;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.enums.StatusEnum;
import com.epam.reportportal.base.infrastructure.persistence.entity.launch.Launch;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.base.reporting.FinishExecutionRQ;
import com.epam.reportportal.base.reporting.ItemAttributesRQ;
import com.epam.reportportal.base.reporting.Mode;
import com.epam.reportportal.base.reporting.StartLaunchRQ;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * Launch lifecycle shared by all import strategies: start (or reuse) a launch, finish it, mark it interrupted on
 * failure.
 */
public abstract class AbstractImportStrategy implements ImportStrategy {

  public static final String SKIPPED_IS_NOT_ISSUE = "skippedIsNotIssue";

  protected final ApplicationEventPublisher eventPublisher;
  protected final LaunchRepository launchRepository;
  protected final TestNgParseService parseService;

  protected AbstractImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository) {
    this.eventPublisher = eventPublisher;
    this.launchRepository = launchRepository;
    this.parseService = new TestNgParseService(eventPublisher);
  }

  protected boolean isImportIntoExistingLaunch(LaunchImportRQ rq) {
    return rq != null && StringUtils.isNotBlank(rq.getLaunchUuid());
  }

  protected String getLaunchUuid(String launchName, String projectName, LaunchImportRQ rq) {
    if (isImportIntoExistingLaunch(rq)) {
      return rq.getLaunchUuid().trim();
    }
    return startLaunch(launchName, projectName, rq);
  }

  protected Instant getExistingLaunchStartTime(LaunchImportRQ rq) {
    if (!isImportIntoExistingLaunch(rq)) {
      return null;
    }
    return getLaunch(rq.getLaunchUuid().trim()).getStartTime();
  }

  private String startLaunch(String launchName, String projectName, LaunchImportRQ rq) {
    LaunchImportRQ request = ofNullable(rq).orElseGet(LaunchImportRQ::new);
    String launchUuid = UUID.randomUUID().toString();
    StartLaunchRQ startLaunchRQ = new StartLaunchRQ();
    startLaunchRQ.setUuid(launchUuid);
    // real start time is set after parsing, when the earliest item time is known
    startLaunchRQ.setStartTime(ofNullable(request.getStartTime()).orElse(Instant.EPOCH));
    startLaunchRQ.setName(StringUtils.defaultIfBlank(request.getName(), launchName));
    startLaunchRQ.setDescription(request.getDescription());
    startLaunchRQ.setMode(ofNullable(request.getMode()).orElse(Mode.DEFAULT));
    startLaunchRQ.setAttributes(ofNullable(request.getAttributes()).orElseGet(HashSet::new));
    eventPublisher.publishEvent(new StartLaunchRqEvent(projectName, startLaunchRQ));
    return launchUuid;
  }

  protected void completeCreatedLaunch(String launchUuid, String projectName, ParseResults results,
      LaunchImportRQ rq) {
    if (isImportIntoExistingLaunch(rq)) {
      return;
    }
    FinishExecutionRQ finishExecutionRQ = new FinishExecutionRQ();
    finishExecutionRQ.setEndTime(results.getEndTime());
    eventPublisher.publishEvent(new FinishLaunchRqEvent(projectName, launchUuid, finishExecutionRQ));

    Launch launch = getLaunch(launchUuid);
    launch.setStartTime(results.getStartTime());
    launchRepository.save(launch);
  }

  /**
   * A created launch which failed to import gets the current start time (instead of the placeholder one) and the
   * INTERRUPTED status, so it doesn't break statistics.
   */
  protected void updateBrokenCreatedLaunch(String launchUuid, LaunchImportRQ rq) {
    if (launchUuid == null || isImportIntoExistingLaunch(rq)) {
      return;
    }
    launchRepository.findByUuid(launchUuid).ifPresent(launch -> {
      launch.setStartTime(Instant.now());
      launch.setStatus(StatusEnum.INTERRUPTED);
      launchRepository.save(launch);
    });
  }

  protected Launch getLaunch(String launchUuid) {
    return launchRepository.findByUuid(launchUuid)
        .orElseThrow(() -> new ReportPortalException(ErrorType.LAUNCH_NOT_FOUND, launchUuid));
  }

  protected boolean isSkippedNotIssue(LaunchImportRQ rq) {
    Set<ItemAttributesRQ> attributes = ofNullable(rq).map(LaunchImportRQ::getAttributes).orElseGet(Set::of);
    return attributes.stream()
        .anyMatch(it -> SKIPPED_IS_NOT_ISSUE.equals(it.getKey()) && it.isSystem()
            && Boolean.parseBoolean(it.getValue()));
  }

  protected String getLaunchName(MultipartFile file) {
    return FilenameUtils.getBaseName(file.getOriginalFilename());
  }

  protected String cleanMessage(Exception e) {
    return e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
  }
}
