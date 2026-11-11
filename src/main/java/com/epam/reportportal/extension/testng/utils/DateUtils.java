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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * Parsing of time values used in {@code testng-results.xml}.
 */
public final class DateUtils {

  /**
   * TestNG writes {@code started-at}/{@code finished-at} as {@code yyyy-MM-dd'T'HH:mm:ss z} (older versions),
   * {@code yyyy-MM-dd'T'HH:mm:ss.SSS z} or {@code yyyy-MM-dd'T'HH:mm:ss'Z'} depending on version and config.
   */
  private static final List<DateTimeFormatter> ZONED_FORMATS = List.of(
      DateTimeFormatter.ISO_OFFSET_DATE_TIME,
      DateTimeFormatter.ISO_ZONED_DATE_TIME,
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS] z", Locale.ENGLISH),
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS] VV", Locale.ENGLISH)
  );

  private DateUtils() {
    //static only
  }

  /**
   * Parses a TestNG timestamp. Values without zone information are treated as UTC.
   *
   * @param value timestamp string or epoch millis
   * @return parsed instant or {@code null} if the value is blank or has an unsupported format
   */
  public static Instant parseTimestamp(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    String timestamp = value.trim();
    if (StringUtils.isNumeric(timestamp)) {
      try {
        return Instant.ofEpochMilli(Long.parseLong(timestamp));
      } catch (NumberFormatException | DateTimeException e) {
        return null;
      }
    }
    for (DateTimeFormatter formatter : ZONED_FORMATS) {
      try {
        return ZonedDateTime.parse(timestamp, formatter).toInstant();
      } catch (DateTimeParseException ignored) {
        // try next format
      }
    }
    try {
      return LocalDateTime.parse(timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * @param value duration in milliseconds
   * @return parsed duration, or {@code null} if blank, not a number, negative or out of the {@code long} range
   */
  public static Long parseDurationMs(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      long duration = Long.parseLong(value.trim());
      return duration < 0 ? null : duration;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
