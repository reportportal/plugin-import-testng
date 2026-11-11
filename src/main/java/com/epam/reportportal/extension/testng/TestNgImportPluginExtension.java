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

package com.epam.reportportal.extension.testng;

import static com.epam.reportportal.extension.util.CommonConstants.DESCRIPTION_KEY;
import static com.epam.reportportal.extension.util.CommonConstants.IS_INTEGRATIONS_ALLOWED;
import static com.epam.reportportal.extension.util.CommonConstants.METADATA;
import static com.epam.reportportal.extension.util.PluginManifestUtils.readPluginIdFromManifest;

import com.epam.reportportal.base.core.events.domain.PluginUploadedEvent;
import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationTypeRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.ProjectUserRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.organization.OrganizationUserRepository;
import com.epam.reportportal.extension.CommonPluginCommand;
import com.epam.reportportal.extension.IntegrationGroupEnum;
import com.epam.reportportal.extension.PluginCommand;
import com.epam.reportportal.extension.ReportPortalExtensionPoint;
import com.epam.reportportal.extension.command.ExtensionCommand;
import com.epam.reportportal.extension.common.IntegrationTypeProperties;
import com.epam.reportportal.extension.testng.command.TestNgImportCommand;
import com.epam.reportportal.extension.testng.event.PluginLoadedEventHandler;
import com.epam.reportportal.extension.util.MemoizingSupplier;
import com.epam.reportportal.extension.util.RequestEntityConverter;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.pf4j.Extension;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Import plugin extension: exposes the {@code import} common command that imports TestNG results
 * ({@code testng-results.xml} or a zip archive of them) as a ReportPortal launch.
 */
@Extension
public class TestNgImportPluginExtension implements ReportPortalExtensionPoint, DisposableBean {

  private static final String DEFAULT_PLUGIN_ID = "testng";
  private static final String PLUGIN_NAME = "TestNG";
  private static final String DESCRIPTION =
      "Reinforce your ReportPortal instance with TestNG Import functionality and easily upload your log files right "
          + "to ReportPortal.";
  private static final String NAME_FIELD = "name";
  private static final List<String> ACCEPTED_MIME_TYPES = List.of("application/zip", "application/x-zip-compressed",
      "application/zip-compressed", "application/xml", "text/xml");

  private final String pluginId;
  private final String resourcesDir;
  private final Supplier<ApplicationListener<PluginUploadedEvent>> pluginLoadedListener;
  private final Supplier<Map<String, ExtensionCommand<?>>> commonCommands =
      new MemoizingSupplier<>(this::createCommonCommands);

  @Autowired
  private ApplicationContext applicationContext;
  @Autowired
  private ApplicationEventPublisher eventPublisher;
  @Autowired
  private IntegrationTypeRepository integrationTypeRepository;
  @Autowired
  private IntegrationRepository integrationRepository;
  @Autowired
  private LaunchRepository launchRepository;
  @Autowired
  private ProjectRepository projectRepository;
  @Autowired
  private ProjectUserRepository projectUserRepository;
  @Autowired
  private OrganizationRepository organizationRepository;
  @Autowired
  private OrganizationUserRepository organizationUserRepository;
  @Autowired
  @Lazy
  private JsonMapper jsonMapper;

  /**
   * @param initParams provided by ReportPortal plugin loader, contains the plugin resources directory
   */
  public TestNgImportPluginExtension(Map<String, Object> initParams) {
    this.pluginId = readPluginIdFromManifest(getClass(), DEFAULT_PLUGIN_ID);
    this.resourcesDir = IntegrationTypeProperties.RESOURCES_DIRECTORY.getValue(initParams)
        .map(String::valueOf).orElse("");
    this.pluginLoadedListener = new MemoizingSupplier<>(() -> new PluginLoadedEventHandler(
        pluginId, resourcesDir, integrationTypeRepository, integrationRepository));
  }

  @PostConstruct
  public void initListeners() {
    getEventMulticaster().addApplicationListener(pluginLoadedListener.get());
  }

  @Override
  public void destroy() {
    getEventMulticaster().removeApplicationListener(pluginLoadedListener.get());
  }

  private ApplicationEventMulticaster getEventMulticaster() {
    return applicationContext.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
        ApplicationEventMulticaster.class);
  }

  @Override
  public Map<String, ?> getPluginParams() {
    Map<String, Object> params = new HashMap<>();
    params.put(NAME_FIELD, PLUGIN_NAME);
    params.put(ALLOWED_COMMANDS, new ArrayList<>());
    params.put(COMMON_COMMANDS, new ArrayList<>(commonCommands.get().keySet()));
    params.put(DESCRIPTION_KEY, DESCRIPTION);
    params.put(METADATA, Map.of(IS_INTEGRATIONS_ALLOWED, false));
    params.put("maxFileSize", TestNgImportCommand.MAX_FILE_SIZE);
    params.put("acceptFileMimeTypes", ACCEPTED_MIME_TYPES);
    return params;
  }

  @Override
  public IntegrationGroupEnum getIntegrationGroup() {
    return IntegrationGroupEnum.IMPORT;
  }

  @Override
  public Map<String, ExtensionCommand<?>> getCommonExtensionCommands() {
    return commonCommands.get();
  }

  @Override
  @Deprecated(forRemoval = true)
  public CommonPluginCommand<?> getCommonCommand(String commandName) {
    return null;
  }

  @Override
  @Deprecated(forRemoval = true)
  public PluginCommand<?> getIntegrationCommand(String commandName) {
    return null;
  }

  private Map<String, ExtensionCommand<?>> createCommonCommands() {
    TestNgImportCommand importCommand = new TestNgImportCommand(new RequestEntityConverter(jsonMapper),
        eventPublisher, launchRepository, projectRepository, organizationUserRepository, organizationRepository,
        projectUserRepository);
    return Map.of(importCommand.getName(), importCommand);
  }
}
