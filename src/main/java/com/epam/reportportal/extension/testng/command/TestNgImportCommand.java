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

package com.epam.reportportal.extension.testng.command;

import static com.epam.reportportal.base.infrastructure.rules.commons.validation.BusinessRule.expect;
import static com.epam.reportportal.base.infrastructure.rules.exception.ErrorType.BAD_REQUEST_ERROR;
import static com.epam.reportportal.base.infrastructure.rules.exception.ErrorType.INCORRECT_REQUEST;
import static com.epam.reportportal.extension.testng.service.FileExtensionConstant.XML_EXTENSION;
import static com.epam.reportportal.extension.testng.service.FileExtensionConstant.ZIP_EXTENSION;
import static com.epam.reportportal.extension.util.CommandParamUtils.ENTITY_PARAM;
import static org.apache.commons.io.FileUtils.ONE_MB;

import com.epam.reportportal.api.model.PluginCommandRQ;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectUserRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationUserRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.organization.OrganizationRole;
import com.epam.reportportal.base.infrastructure.persistence.entity.project.ProjectRole;
import com.epam.reportportal.base.infrastructure.persistence.entity.user.UserRole;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.base.reporting.StartLaunchRS;
import com.epam.reportportal.extension.command.AbstractExtensionCommand;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import com.epam.reportportal.extension.testng.service.ImportStrategyFactory;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code import} command: imports a {@code testng-results.xml} file or a zip archive of them as a launch.
 *
 * <p>Arguments: {@code file} (multipart file, required), {@code projectName} (required) and an optional
 * {@code entity} with {@link LaunchImportRQ} launch parameters.
 */
public class TestNgImportCommand extends AbstractExtensionCommand<StartLaunchRS> {

  public static final String NAME = "import";
  public static final long MAX_FILE_SIZE = 32L * ONE_MB;

  private static final String FILE_PARAM = "file";
  private static final String PROJECT_NAME_PARAM = "projectName";

  private final RequestEntityConverter requestEntityConverter;
  private final ImportStrategyFactory importStrategyFactory;

  public TestNgImportCommand(RequestEntityConverter requestEntityConverter,
    ApplicationEventPublisher eventPublisher,
    LaunchRepository launchRepository,
    ProjectRepository projectRepository,
    OrganizationUserRepository organizationUserRepository,
    OrganizationRepository organizationRepository,
    ProjectUserRepository projectUserRepository) {
    super(projectRepository, organizationUserRepository, organizationRepository, projectUserRepository);
    this.requestEntityConverter = requestEntityConverter;
    this.importStrategyFactory = new ImportStrategyFactory(eventPublisher, launchRepository);

    this.minProjectRole = ProjectRole.EDITOR;
    this.minOrgRole = OrganizationRole.MANAGER;
    this.minUserRole = UserRole.ADMINISTRATOR;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  protected StartLaunchRS invokeCommand(PluginCommandRQ pluginCommandRq) {
    Map<String, Object> params = pluginCommandRq.getArguments();

    LaunchImportRQ launchImportRQ = Optional.ofNullable(params.get(ENTITY_PARAM))
      .map(it -> requestEntityConverter.getEntity(ENTITY_PARAM, params, LaunchImportRQ.class))
      .orElseGet(LaunchImportRQ::new);
    MultipartFile file = Optional.ofNullable(params.get(FILE_PARAM))
      .filter(MultipartFile.class::isInstance)
      .map(MultipartFile.class::cast)
      .orElseThrow(() -> new ReportPortalException(BAD_REQUEST_ERROR, "File for import wasn't provided"));
    String projectName = Optional.ofNullable(params.get(PROJECT_NAME_PARAM)).map(String::valueOf)
      .orElseThrow(() -> new ReportPortalException(BAD_REQUEST_ERROR, "Project name wasn't provided"));
    validate(file);

    String launchUuid = importStrategyFactory.getImportStrategy(file.getOriginalFilename())
      .importLaunch(file, projectName, launchImportRQ);

    StartLaunchRS response = new StartLaunchRS();
    response.setId(launchUuid);
    return response;
  }

  private void validate(MultipartFile file) {
    expect(file.getOriginalFilename(), Objects::nonNull)
      .verify(INCORRECT_REQUEST, "File name should be not empty.");
    String fileName = file.getOriginalFilename().toLowerCase(Locale.ROOT);
    expect(fileName, it -> it.endsWith("." + ZIP_EXTENSION) || it.endsWith("." + XML_EXTENSION))
      .verify(INCORRECT_REQUEST, "Should be a zip archive or an xml file " + file.getOriginalFilename());
    expect(file.getSize(), size -> size <= MAX_FILE_SIZE)
      .verify(INCORRECT_REQUEST, "File size is more than 32 Mb.");
  }
}
