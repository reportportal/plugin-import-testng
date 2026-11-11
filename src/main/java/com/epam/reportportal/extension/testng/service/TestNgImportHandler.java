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

import static com.epam.reportportal.base.infrastructure.persistence.entity.enums.TestItemIssueGroup.NOT_ISSUE_FLAG;

import com.epam.reportportal.base.infrastructure.events.FinishItemRqEvent;
import com.epam.reportportal.base.infrastructure.events.SaveLogRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartChildItemRqEvent;
import com.epam.reportportal.base.infrastructure.events.StartRootItemRqEvent;
import com.epam.reportportal.base.infrastructure.persistence.entity.enums.LogLevel;
import com.epam.reportportal.base.infrastructure.persistence.entity.enums.StatusEnum;
import com.epam.reportportal.base.infrastructure.persistence.entity.enums.TestItemTypeEnum;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.base.reporting.FinishTestItemRQ;
import com.epam.reportportal.base.reporting.Issue;
import com.epam.reportportal.base.reporting.ItemAttributesRQ;
import com.epam.reportportal.base.reporting.ParameterResource;
import com.epam.reportportal.base.reporting.SaveLogRQ;
import com.epam.reportportal.base.reporting.StartTestItemRQ;
import com.epam.reportportal.extension.testng.model.ItemInfo;
import com.epam.reportportal.extension.testng.model.TestMethodInfo;
import com.epam.reportportal.extension.testng.service.TestNgReportTag.Attr;
import com.epam.reportportal.extension.testng.utils.DateUtils;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX handler that converts a {@code testng-results.xml} report into ReportPortal reporting events.
 *
 * <p>Hierarchy is flattened to {@code Launch -> SUITE -> TEST -> (BEFORE_METHOD | STEP | AFTER_METHOD)}:
 * {@code <class>} and {@code <groups>} don't create items.
 *
 * <ul>
 *   <li>Times: start = {@code started-at}, end = {@code started-at + duration-ms} ({@code finished-at} has only
 *   second resolution and is used only when {@code duration-ms} is missing).</li>
 *   <li>Configuration methods ({@code is-config="true"}) are BEFORE_METHOD when they precede the first regular
 *   method of their class and AFTER_METHOD otherwise.</li>
 *   <li>A method with {@code retried="true"} was re-run by TestNG: the next execution of the same method (same class,
 *   name and parameters) is reported with {@code retryOf} pointing to it, so ReportPortal shows the earlier
 *   executions as retries of the last one.</li>
 *   <li>{@code <reporter-output>} lines become INFO logs, {@code <exception>} becomes the last ERROR log; log times are
 *   shifted by 1 microsecond each to keep their order.</li>
 * </ul>
 */
public class TestNgImportHandler extends DefaultHandler {

  static final int MAX_ENTITY_NAME_LENGTH = 1024;
  private static final String NO_NAME = "no_name";
  private static final String NULL_VALUE = "NULL";

  private final ApplicationEventPublisher eventPublisher;
  private final String projectName;
  private final String launchUuid;
  private final boolean skippedIsNotIssue;
  private final Instant launchStartTime;

  private final Deque<ItemInfo> items = new ArrayDeque<>();
  private final StringBuilder text = new StringBuilder();
  /**
   * Uuids of executions marked {@code retried="true"} in the current {@code <test>}, by retry key.
   */
  private final Map<String, String> retriedExecutions = new HashMap<>();

  private boolean rootVerified;
  private String currentClassName;
  private boolean regularMethodSeenInClass;
  private TestMethodInfo currentMethod;
  private String currentParamIndex;
  private String currentAttributeName;

  /**
   * Earliest suite start and latest suite end, used as launch start/end.
   */
  @Getter
  private Instant lowestTime;
  @Getter
  private Instant highestTime;

  public TestNgImportHandler(ApplicationEventPublisher eventPublisher, String projectName, String launchUuid,
      boolean skippedIsNotIssue, Instant launchStartTime) {
    this.eventPublisher = eventPublisher;
    this.projectName = projectName;
    this.launchUuid = launchUuid;
    this.skippedIsNotIssue = skippedIsNotIssue;
    this.launchStartTime = launchStartTime;
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes attributes)
      throws SAXException {
    TestNgReportTag tag = TestNgReportTag.fromString(qName);
    if (!rootVerified) {
      if (tag != TestNgReportTag.TESTNG_RESULTS) {
        throw new NotTestNgReportException(qName);
      }
      rootVerified = true;
      return;
    }
    text.setLength(0);
    switch (tag) {
      case SUITE -> startContainer(attributes, TestItemTypeEnum.SUITE);
      case TEST -> {
        startContainer(attributes, TestItemTypeEnum.TEST);
        retriedExecutions.clear();
      }
      case CLASS -> {
        currentClassName = attributes.getValue(Attr.NAME);
        regularMethodSeenInClass = false;
      }
      case TEST_METHOD -> currentMethod = readTestMethod(attributes);
      case PARAM -> currentParamIndex = attributes.getValue(Attr.INDEX);
      case VALUE -> {
        if (Boolean.parseBoolean(attributes.getValue(Attr.IS_NULL))) {
          addParameter(NULL_VALUE);
          currentParamIndex = null;
        }
      }
      case EXCEPTION -> {
        if (currentMethod != null) {
          currentMethod.setExceptionClass(attributes.getValue(Attr.CLASS));
        }
      }
      case ATTRIBUTE -> currentAttributeName = attributes.getValue(Attr.NAME);
      default -> {
        // text-only or ignored elements (groups, class level, ...)
      }
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName) {
    switch (TestNgReportTag.fromString(qName)) {
      case SUITE -> registerSuiteTimes(finishContainer());
      case TEST -> finishContainer();
      case CLASS -> currentClassName = null;
      case TEST_METHOD -> reportTestMethod();
      case VALUE -> {
        if (currentParamIndex != null) {
          addParameter(text.toString().trim());
          currentParamIndex = null;
        }
      }
      case MESSAGE -> {
        if (currentMethod != null) {
          currentMethod.setExceptionMessage(text.toString().trim());
        }
      }
      case FULL_STACKTRACE -> {
        if (currentMethod != null) {
          currentMethod.setExceptionStacktrace(text.toString().trim());
        }
      }
      case LINE -> {
        if (currentMethod != null && StringUtils.isNotBlank(text)) {
          currentMethod.getReporterOutput().add(text.toString().trim());
        }
      }
      case ATTRIBUTE -> {
        if (currentMethod != null && StringUtils.isNotBlank(currentAttributeName)) {
          String value = text.toString().trim();
          currentMethod.getAttributes().add(value.isEmpty()
              ? new ItemAttributesRQ(currentAttributeName)
              : new ItemAttributesRQ(currentAttributeName, value));
        }
        currentAttributeName = null;
      }
      default -> {
        // nothing to finish
      }
    }
    text.setLength(0);
  }

  @Override
  public void characters(char[] ch, int start, int length) {
    text.append(ch, start, length);
  }

  private void startContainer(Attributes attributes, TestItemTypeEnum type) {
    ItemInfo parent = items.peek();
    Instant startTime = resolveStartTime(attributes, parent);
    validateItemTime(startTime);

    StartTestItemRQ rq = new StartTestItemRQ();
    rq.setUuid(UUID.randomUUID().toString());
    rq.setLaunchUuid(launchUuid);
    rq.setType(type.name());
    rq.setName(abbreviateName(attributes.getValue(Attr.NAME)));
    rq.setStartTime(startTime);

    if (parent == null) {
      eventPublisher.publishEvent(new StartRootItemRqEvent(projectName, rq));
    } else {
      eventPublisher.publishEvent(new StartChildItemRqEvent(projectName, parent.getUuid(), rq));
    }

    ItemInfo item = new ItemInfo(rq.getUuid(), startTime);
    item.setDeclaredEndTime(resolveDeclaredEnd(startTime, attributes));
    items.push(item);
  }

  private ItemInfo finishContainer() {
    ItemInfo item = items.poll();
    if (item == null) {
      return null;
    }
    Instant endTime = item.resolveEndTime();

    FinishTestItemRQ rq = new FinishTestItemRQ();
    rq.setLaunchUuid(launchUuid);
    rq.setEndTime(endTime);
    eventPublisher.publishEvent(new FinishItemRqEvent(projectName, item.getUuid(), rq));

    if (items.peek() != null) {
      items.peek().registerChildEnd(endTime);
    }
    return item;
  }

  private void registerSuiteTimes(ItemInfo suite) {
    if (suite == null) {
      return;
    }
    Instant end = suite.resolveEndTime();
    if (lowestTime == null || suite.getStartTime().isBefore(lowestTime)) {
      lowestTime = suite.getStartTime();
    }
    if (highestTime == null || end.isAfter(highestTime)) {
      highestTime = end;
    }
  }

  private TestMethodInfo readTestMethod(Attributes attributes) {
    TestMethodInfo method = new TestMethodInfo();
    method.setName(attributes.getValue(Attr.NAME));
    method.setClassName(currentClassName);
    method.setDescription(attributes.getValue(Attr.DESCRIPTION));
    method.setStatus(attributes.getValue(Attr.STATUS));
    method.setConfig(Boolean.parseBoolean(attributes.getValue(Attr.IS_CONFIG)));
    method.setRetried(Boolean.parseBoolean(attributes.getValue(Attr.RETRIED)));

    Instant startTime = resolveStartTime(attributes, items.peek());
    method.setStartTime(startTime);
    method.setEndTime(Objects.requireNonNullElse(resolveDeclaredEnd(startTime, attributes), startTime));
    return method;
  }

  private void reportTestMethod() {
    TestMethodInfo method = currentMethod;
    currentMethod = null;
    ItemInfo parent = items.peek();
    if (method == null || parent == null) {
      return;
    }
    validateItemTime(method.getStartTime());

    StartTestItemRQ startRq = new StartTestItemRQ();
    startRq.setUuid(UUID.randomUUID().toString());
    startRq.setLaunchUuid(launchUuid);
    startRq.setType(resolveMethodType(method).name());
    startRq.setName(abbreviateName(method.getName()));
    startRq.setStartTime(method.getStartTime());
    startRq.setCodeRef(buildCodeRef(method));
    startRq.setDescription(buildDescription(method));
    if (!method.getParameters().isEmpty()) {
      startRq.setParameters(method.getParameters());
    }
    if (!method.getAttributes().isEmpty()) {
      startRq.setAttributes(new LinkedHashSet<>(method.getAttributes()));
    }
    String retryKey = buildRetryKey(method);
    String retryOf = retriedExecutions.remove(retryKey);
    if (retryOf != null) {
      startRq.setRetry(true);
      startRq.setRetryOf(retryOf);
    }
    eventPublisher.publishEvent(new StartChildItemRqEvent(projectName, parent.getUuid(), startRq));
    if (method.isRetried()) {
      retriedExecutions.put(retryKey, startRq.getUuid());
    }

    Instant endTime = reportLogs(startRq.getUuid(), method);

    StatusEnum status = mapStatus(method.getStatus());
    FinishTestItemRQ finishRq = new FinishTestItemRQ();
    finishRq.setLaunchUuid(launchUuid);
    finishRq.setEndTime(endTime);
    finishRq.setStatus(status.name());
    if (status == StatusEnum.SKIPPED && skippedIsNotIssue) {
      Issue issue = new Issue();
      issue.setIssueType(NOT_ISSUE_FLAG.getValue());
      finishRq.setIssue(issue);
    }
    eventPublisher.publishEvent(new FinishItemRqEvent(projectName, startRq.getUuid(), finishRq));

    parent.registerChildEnd(endTime);
  }

  /**
   * Reports reporter output lines (INFO) and the exception (ERROR, last), each 1 microsecond after the previous log so
   * ReportPortal keeps their order even for zero-duration methods.
   *
   * @return item end time, moved forward if logs don't fit into the declared duration
   */
  private Instant reportLogs(String itemUuid, TestMethodInfo method) {
    Instant logTime = method.getStartTime();
    for (String line : method.getReporterOutput()) {
      saveLog(itemUuid, logTime, LogLevel.INFO, line);
      logTime = logTime.plus(1, ChronoUnit.MICROS);
    }
    Instant endTime = method.getEndTime().isBefore(logTime) ? logTime : method.getEndTime();
    String error = buildErrorLog(method);
    if (error != null) {
      saveLog(itemUuid, endTime, LogLevel.ERROR, error);
    }
    return endTime;
  }

  private void addParameter(String value) {
    if (currentMethod != null) {
      ParameterResource parameter = new ParameterResource();
      parameter.setKey(Objects.toString(currentParamIndex, String.valueOf(currentMethod.getParameters().size())));
      parameter.setValue(value);
      currentMethod.getParameters().add(parameter);
    }
  }

  private void saveLog(String itemUuid, Instant time, LogLevel level, String message) {
    SaveLogRQ rq = new SaveLogRQ();
    rq.setUuid(UUID.randomUUID().toString());
    rq.setLaunchUuid(launchUuid);
    rq.setItemUuid(itemUuid);
    rq.setLogTime(time);
    rq.setLevel(level.name());
    rq.setMessage(message);
    eventPublisher.publishEvent(new SaveLogRqEvent(projectName, rq, null));
  }

  /**
   * @return exception message followed by the full stack trace, or {@code null} if the method has no exception
   */
  static String buildErrorLog(TestMethodInfo method) {
    String message = exceptionHeadline(method);
    String stacktrace = StringUtils.trimToNull(method.getExceptionStacktrace());
    if (stacktrace == null) {
      return message;
    }
    if (message == null || stacktrace.contains(StringUtils.defaultString(method.getExceptionMessage()))) {
      return stacktrace;
    }
    return message + "\n" + stacktrace;
  }

  /**
   * Step description followed by the exception headline, so the failure reason is visible without opening logs.
   */
  static String buildDescription(TestMethodInfo method) {
    String description = StringUtils.trimToNull(method.getDescription());
    String error = exceptionHeadline(method);
    if (error == null) {
      return description;
    }
    String errorBlock = "**Error:**\n```\n" + error + "\n```";
    return description == null ? errorBlock : description + "\n\n" + errorBlock;
  }

  private static String exceptionHeadline(TestMethodInfo method) {
    String exceptionClass = StringUtils.trimToNull(method.getExceptionClass());
    String message = StringUtils.trimToNull(method.getExceptionMessage());
    if (exceptionClass != null && message != null) {
      return exceptionClass + ": " + message;
    }
    return exceptionClass != null ? exceptionClass : message;
  }

  private TestItemTypeEnum resolveMethodType(TestMethodInfo method) {
    if (!method.isConfig()) {
      regularMethodSeenInClass = true;
      return TestItemTypeEnum.STEP;
    }
    return regularMethodSeenInClass ? TestItemTypeEnum.AFTER_METHOD : TestItemTypeEnum.BEFORE_METHOD;
  }

  private static String buildCodeRef(TestMethodInfo method) {
    return StringUtils.isBlank(method.getClassName()) ? method.getName()
        : method.getClassName() + "." + method.getName();
  }

  private static String buildRetryKey(TestMethodInfo method) {
    List<String> values = new ArrayList<>();
    method.getParameters().forEach(it -> values.add(it.getKey() + "=" + it.getValue()));
    return buildCodeRef(method) + values.stream().collect(Collectors.joining(",", "[", "]"));
  }

  static StatusEnum mapStatus(String status) {
    if (status == null) {
      return StatusEnum.FAILED;
    }
    return switch (status.trim().toUpperCase(Locale.ROOT)) {
      case "PASS" -> StatusEnum.PASSED;
      case "SKIP" -> StatusEnum.SKIPPED;
      default -> StatusEnum.FAILED;
    };
  }

  /**
   * {@code started-at}, but never earlier than the parent's start: ReportPortal rejects such children
   * ({@code CHILD_START_TIME_EARLIER_THAN_PARENT}). TestNG reports e.g. a {@code @BeforeClass} method of a class
   * shared between {@code <test>}s with its own (earlier) time, while the flattened hierarchy puts it under the test.
   */
  private static Instant resolveStartTime(Attributes attributes, ItemInfo parent) {
    Instant declared = DateUtils.parseTimestamp(attributes.getValue(Attr.STARTED_AT));
    if (declared == null) {
      return parent != null ? parent.getCursorTime() : Instant.now();
    }
    return parent != null && declared.isBefore(parent.getStartTime()) ? parent.getStartTime() : declared;
  }

  /**
   * {@code started-at + duration-ms}; falls back to {@code finished-at} when the duration is absent or invalid.
   */
  private static Instant resolveDeclaredEnd(Instant startTime, Attributes attributes) {
    Long duration = DateUtils.parseDurationMs(attributes.getValue(Attr.DURATION_MS));
    if (duration != null) {
      try {
        return startTime.plusMillis(duration);
      } catch (DateTimeException | ArithmeticException e) {
        // beyond the supported time range: fall back to finished-at
      }
    }
    Instant finishedAt = DateUtils.parseTimestamp(attributes.getValue(Attr.FINISHED_AT));
    return finishedAt != null && !finishedAt.isBefore(startTime) ? finishedAt : null;
  }


  private static String abbreviateName(String name) {
    return StringUtils.isBlank(name) ? NO_NAME : StringUtils.abbreviate(name, MAX_ENTITY_NAME_LENGTH);
  }

  private void validateItemTime(Instant itemTime) {
    if (launchStartTime != null && itemTime.isBefore(launchStartTime)) {
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
          String.format("Item start time '%s' is earlier than launch start time '%s'", itemTime, launchStartTime));
    }
  }
}
