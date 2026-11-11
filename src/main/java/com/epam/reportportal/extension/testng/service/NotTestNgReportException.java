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

import org.xml.sax.SAXException;

/**
 * Thrown when the root element of an XML file is not {@code testng-results}. It is raised before any
 * reporting event is published, so the file can be safely skipped.
 */
public class NotTestNgReportException extends SAXException {

  public NotTestNgReportException(String rootElement) {
    super("Root node of TestNG results file must be 'testng-results', but was '" + rootElement + "'");
  }
}
