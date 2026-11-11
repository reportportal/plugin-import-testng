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

import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imports a launch from an uploaded file.
 */
public interface ImportStrategy {

  /**
   * @param file        an uploaded {@code testng-results.xml} file or a zip archive with such files
   * @param projectName project name
   * @param rq          launch import request
   * @return launch uuid
   */
  String importLaunch(MultipartFile file, String projectName, LaunchImportRQ rq);
}
