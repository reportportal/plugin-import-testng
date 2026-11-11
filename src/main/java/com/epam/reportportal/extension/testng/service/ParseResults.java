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

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import lombok.Getter;

/**
 * Time boundaries of the parsed report(s), used to set launch start and end times.
 */
@Getter
public class ParseResults {

  private final Instant startTime;
  private final Instant endTime;

  public ParseResults(Instant startTime, Instant endTime) {
    this.startTime = startTime;
    this.endTime = endTime;
  }

  /**
   * Merges results of several parsed files into a single time range.
   */
  public static ParseResults merge(Collection<ParseResults> results) {
    Instant start = results.stream().map(ParseResults::getStartTime).filter(Objects::nonNull)
        .min(Instant::compareTo).orElseGet(Instant::now);
    Instant end = results.stream().map(ParseResults::getEndTime).filter(Objects::nonNull)
        .max(Instant::compareTo).orElse(start);
    return new ParseResults(start, end);
  }
}
