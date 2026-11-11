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

package com.epam.reportportal.extension.testng.event;

import static com.epam.reportportal.extension.util.CommonConstants.BINARY_DATA;

import com.epam.reportportal.base.core.events.domain.PluginUploadedEvent;
import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationRepository;
import com.epam.reportportal.base.infrastructure.persistence.dao.IntegrationTypeRepository;
import com.epam.reportportal.base.infrastructure.persistence.entity.integration.Integration;
import com.epam.reportportal.base.infrastructure.persistence.entity.integration.IntegrationParams;
import com.epam.reportportal.base.infrastructure.persistence.entity.integration.IntegrationType;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.context.ApplicationListener;

/**
 * On plugin upload creates the global integration (import plugins need exactly one, it isn't configurable by
 * users) and registers UI binary data from {@code binary-data.properties}.
 */
public class PluginLoadedEventHandler implements ApplicationListener<PluginUploadedEvent> {

  public static final String BINARY_DATA_PROPERTIES_FILE = "binary-data.properties";

  private final String pluginId;
  private final String resourcesDir;
  private final IntegrationTypeRepository integrationTypeRepository;
  private final IntegrationRepository integrationRepository;

  public PluginLoadedEventHandler(String pluginId, String resourcesDir,
      IntegrationTypeRepository integrationTypeRepository, IntegrationRepository integrationRepository) {
    this.pluginId = pluginId;
    this.resourcesDir = resourcesDir;
    this.integrationTypeRepository = integrationTypeRepository;
    this.integrationRepository = integrationRepository;
  }

  @Override
  public void onApplicationEvent(PluginUploadedEvent event) {
    String name = event.getPluginActivityResource().getName();
    if (!pluginId.equals(name)) {
      return;
    }
    integrationTypeRepository.findByName(name).ifPresent(integrationType -> {
      createIntegration(name, integrationType);
      loadBinaryDataInfo(integrationType);
    });
  }

  private void createIntegration(String name, IntegrationType integrationType) {
    if (integrationRepository.findAllGlobalByType(integrationType).isEmpty()) {
      Integration integration = new Integration();
      integration.setName(name);
      integration.setType(integrationType);
      integration.setCreationDate(Instant.now());
      integration.setEnabled(true);
      integration.setCreator("SYSTEM");
      integration.setParams(new IntegrationParams(new HashMap<>()));
      integrationRepository.save(integration);
    }
  }

  private void loadBinaryDataInfo(IntegrationType integrationType) {
    Map<String, Object> details = integrationType.getDetails().getDetails();
    if (details.get(BINARY_DATA) != null) {
      return;
    }
    try (InputStream propertiesStream = Files.newInputStream(Paths.get(resourcesDir, BINARY_DATA_PROPERTIES_FILE))) {
      Properties properties = new Properties();
      properties.load(propertiesStream);
      Map<String, String> binaryData = new HashMap<>();
      properties.forEach((key, value) -> binaryData.put(String.valueOf(key), String.valueOf(value)));
      details.put(BINARY_DATA, binaryData);
      integrationTypeRepository.save(integrationType);
    } catch (IOException ex) {
      throw new ReportPortalException(ErrorType.UNABLE_TO_LOAD_BINARY_DATA, ex.getMessage());
    }
  }
}
