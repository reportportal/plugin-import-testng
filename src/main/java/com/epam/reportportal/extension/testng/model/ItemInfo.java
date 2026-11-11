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

package com.epam.reportportal.extension.testng.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A started container item (suite, test or class) that is still open while the report is parsed.
 */
@Getter
@Setter
public class ItemInfo {

  private final String uuid;
  private final Instant startTime;

  /**
   * End time declared in the report ({@code finished-at} or {@code started-at + duration-ms}), may be null.
   */
  private Instant declaredEndTime;

  /**
   * The latest end time of the item's children.
   */
  private Instant lastChildEndTime;

  public ItemInfo(String uuid, Instant startTime) {
    this.uuid = uuid;
    this.startTime = startTime;
  }

  /**
   * @return time the next child item should start at if the report doesn't declare it
   */
  public Instant getCursorTime() {
    return lastChildEndTime != null ? lastChildEndTime : startTime;
  }

  public void registerChildEnd(Instant childEnd) {
    if (childEnd != null && (lastChildEndTime == null || childEnd.isAfter(lastChildEndTime))) {
      lastChildEndTime = childEnd;
    }
  }

  public Instant resolveEndTime() {
    Instant end = declaredEndTime != null ? declaredEndTime : startTime;
    if (lastChildEndTime != null && lastChildEndTime.isAfter(end)) {
      end = lastChildEndTime;
    }
    return end;
  }
}
