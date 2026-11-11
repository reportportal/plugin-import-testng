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

package com.epam.reportportal.extension.testng.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DateUtilsTest {

  @ParameterizedTest
  @CsvSource({
      "2024-03-01T10:00:00 UTC, 2024-03-01T10:00:00Z",
      "2024-03-01T10:00:00.250 UTC, 2024-03-01T10:00:00.250Z",
      "2024-03-01T13:00:00 Europe/Minsk, 2024-03-01T10:00:00Z",
      "2024-03-01T10:00:00Z, 2024-03-01T10:00:00Z",
      "2024-03-01T12:00:00+02:00, 2024-03-01T10:00:00Z",
      "2024-03-01T10:00:00, 2024-03-01T10:00:00Z",
      "1709287200000, 2024-03-01T10:00:00Z"
  })
  void shouldParseSupportedFormats(String value, String expected) {
    assertEquals(Instant.parse(expected), DateUtils.parseTimestamp(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "yesterday", "99999999999999999999"})
  void shouldReturnNullForUnsupportedValues(String value) {
    assertNull(DateUtils.parseTimestamp(value));
  }

  @ParameterizedTest
  @CsvSource({"1500, 1500", "0, 0", "' 20 ', 20"})
  void shouldParseDuration(String value, long expected) {
    assertEquals(expected, DateUtils.parseDurationMs(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "-5", "99999999999999999999"})
  void shouldReturnNullForInvalidDuration(String value) {
    assertNull(DateUtils.parseDurationMs(value));
  }
}
