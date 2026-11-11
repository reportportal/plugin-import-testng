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

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * Elements of the {@code testng-results.xml} report produced by TestNG {@code XMLReporter}.
 */
@Getter
public enum TestNgReportTag {

  TESTNG_RESULTS("testng-results"),
  SUITE("suite"),
  TEST("test"),
  CLASS("class"),
  TEST_METHOD("test-method"),
  PARAMS("params"),
  PARAM("param"),
  VALUE("value"),
  EXCEPTION("exception"),
  MESSAGE("message"),
  FULL_STACKTRACE("full-stacktrace"),
  REPORTER_OUTPUT("reporter-output"),
  LINE("line"),
  ATTRIBUTES("attributes"),
  ATTRIBUTE("attribute"),
  UNKNOWN("");

  private static final Map<String, TestNgReportTag> BY_VALUE = Arrays.stream(values())
      .collect(Collectors.toMap(TestNgReportTag::getValue, Function.identity()));

  private final String value;

  TestNgReportTag(String value) {
    this.value = value;
  }

  public static TestNgReportTag fromString(String tag) {
    return BY_VALUE.getOrDefault(tag, UNKNOWN);
  }

  /**
   * Attribute names used on report elements.
   */
  public static final class Attr {

    public static final String NAME = "name";
    public static final String RETRIED = "retried";
    public static final String STATUS = "status";
    public static final String DESCRIPTION = "description";
    public static final String IS_CONFIG = "is-config";
    public static final String STARTED_AT = "started-at";
    public static final String FINISHED_AT = "finished-at";
    public static final String DURATION_MS = "duration-ms";
    public static final String CLASS = "class";
    public static final String INDEX = "index";
    public static final String IS_NULL = "is-null";

    private Attr() {
    }
  }
}
