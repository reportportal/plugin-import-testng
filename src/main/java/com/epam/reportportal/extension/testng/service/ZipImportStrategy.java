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

import static com.epam.reportportal.extension.testng.service.FileExtensionConstant.XML_EXTENSION;

import com.epam.reportportal.base.infrastructure.persistence.dao.LaunchRepository;
import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import com.epam.reportportal.extension.testng.model.LaunchImportRQ;
import com.epam.reportportal.extension.testng.utils.UncompressedSizeLimit;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imports all {@code testng-results} XML files of a zip archive into one combined launch. XML files of other formats
 * (e.g. JUnit reports that TestNG/Surefire write next to {@code testng-results.xml}) are skipped; an archive without
 * any TestNG XML is an error. The number of entries and the total uncompressed size are limited.
 */
@Slf4j
public class ZipImportStrategy extends AbstractImportStrategy {

  /**
   * Zip bomb protection: the upload itself is limited to 32 MB, these limits apply to its content.
   */
  static final int MAX_ENTRIES = 10_000;
  static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

  private final int maxEntries;
  private final long maxUncompressedBytes;

  public ZipImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository) {
    this(eventPublisher, launchRepository, MAX_ENTRIES, MAX_UNCOMPRESSED_BYTES);
  }

  ZipImportStrategy(ApplicationEventPublisher eventPublisher, LaunchRepository launchRepository, int maxEntries,
      long maxUncompressedBytes) {
    super(eventPublisher, launchRepository);
    this.maxEntries = maxEntries;
    this.maxUncompressedBytes = maxUncompressedBytes;
  }

  @Override
  public String importLaunch(MultipartFile file, String projectName, LaunchImportRQ rq) {
    String launchUuid = null;
    File zip = transferToTempFile(file);
    try (ZipFile zipFile = new ZipFile(zip)) {
      if (zipFile.size() > maxEntries) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "Archive contains more than " + maxEntries + " entries");
      }
      UncompressedSizeLimit sizeLimit = new UncompressedSizeLimit(maxUncompressedBytes);
      // selected before the launch is created, so an archive without TestNG results doesn't leave an empty launch
      List<ZipEntry> reportEntries = findTestNgReports(zipFile, sizeLimit);
      if (reportEntries.isEmpty()) {
        throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR,
            "Archive doesn't contain any TestNG results XML file (root element 'testng-results')");
      }

      Instant launchStartTime = getExistingLaunchStartTime(rq);
      launchUuid = getLaunchUuid(getLaunchName(file), projectName, rq);
      boolean skippedIsNotIssue = isSkippedNotIssue(rq);

      List<ParseResults> results = new ArrayList<>();
      for (ZipEntry entry : reportEntries) {
        try (InputStream entryStream = sizeLimit.wrap(zipFile.getInputStream(entry))) {
          results.add(parseService.parse(entryStream, launchUuid, projectName, skippedIsNotIssue, launchStartTime));
        }
      }
      completeCreatedLaunch(launchUuid, projectName, ParseResults.merge(results), rq);
      return launchUuid;
    } catch (Exception e) {
      log.error("Failed to import TestNG ZIP file '{}' into launch '{}' for project '{}'",
          file.getOriginalFilename(), launchUuid, projectName, e);
      updateBrokenCreatedLaunch(launchUuid, rq);
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, cleanMessage(e));
    } finally {
      try {
        Files.deleteIfExists(zip.toPath());
      } catch (IOException e) {
        log.warn("Failed to delete temporary ZIP file '{}'", zip.getAbsolutePath(), e);
      }
    }
  }

  private List<ZipEntry> findTestNgReports(ZipFile zipFile, UncompressedSizeLimit sizeLimit) throws IOException {
    List<ZipEntry> reports = new ArrayList<>();
    for (ZipEntry entry : Collections.list(zipFile.entries())) {
      if (!isReportCandidate(entry)) {
        continue;
      }
      try (InputStream entryStream = sizeLimit.wrap(zipFile.getInputStream(entry))) {
        if (parseService.isTestNgReport(entryStream)) {
          reports.add(entry);
        } else {
          log.debug("Skipping '{}': not a TestNG results file", entry.getName());
        }
      }
    }
    return reports;
  }

  /**
   * Only XML files are processed; macOS archive metadata ({@code __MACOSX/}, {@code .DS_Store}, {@code ._*}) and
   * any other files (attachments, images, ...) are ignored.
   */
  static boolean isReportCandidate(ZipEntry entry) {
    String path = entry.getName().replace('\\', '/');
    String fileName = path.substring(path.lastIndexOf('/') + 1);
    return !entry.isDirectory()
        && !path.contains("_MACOSX")
        && !fileName.equals(".DS_Store")
        && !fileName.startsWith("._")
        && fileName.toLowerCase(Locale.ROOT).endsWith("." + XML_EXTENSION);
  }

  private File transferToTempFile(MultipartFile file) {
    try {
      File tmp = Files.createTempFile("testng-import-", ".zip").toFile();
      file.transferTo(tmp);
      return tmp;
    } catch (IOException e) {
      throw new ReportPortalException(ErrorType.IMPORT_FILE_ERROR, "Error during transferring multipart file.");
    }
  }
}
