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

import com.epam.reportportal.base.infrastructure.rules.exception.ErrorType;
import com.epam.reportportal.base.infrastructure.rules.exception.ReportPortalException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Parses a single {@code testng-results.xml} stream and publishes reporting events into the given launch.
 */
public class TestNgParseService {

  private final ApplicationEventPublisher eventPublisher;

  public TestNgParseService(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  /**
   * @throws NotTestNgReportException if the root element is not {@code testng-results}; nothing is reported then
   */
  public ParseResults parse(InputStream inputStream, String launchUuid, String projectName,
      boolean skippedIsNotIssue, Instant launchStartTime) throws NotTestNgReportException {
    TestNgImportHandler handler = new TestNgImportHandler(eventPublisher, projectName, launchUuid,
        skippedIsNotIssue, launchStartTime);
    try {
      newSecureParser().parse(inputStream, handler);
    } catch (NotTestNgReportException e) {
      throw e;
    } catch (SAXException | IOException | ParserConfigurationException e) {
      throw new ReportPortalException(ErrorType.PARSING_XML_ERROR, e.getMessage());
    }
    return new ParseResults(handler.getLowestTime(), handler.getHighestTime());
  }

  /**
   * Reads the stream only up to its root element, so the file type can be checked before a launch is created.
   *
   * @return {@code true} if the root element is {@code testng-results}; {@code false} for other or malformed XML
   * @throws IOException if reading the stream fails (including exceeded size limits)
   */
  public boolean isTestNgReport(InputStream inputStream) throws IOException {
    RootElementHandler handler = new RootElementHandler();
    try {
      newSecureParser().parse(inputStream, handler);
    } catch (RootElementFound found) {
      return TestNgReportTag.TESTNG_RESULTS.getValue().equals(found.rootElement);
    } catch (SAXException | ParserConfigurationException e) {
      return false;
    }
    return false;
  }

  /**
   * Creates a parser protected against XXE: DOCTYPE declarations and external entities are disabled.
   */
  private static SAXParser newSecureParser() throws ParserConfigurationException, SAXException {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    return factory.newSAXParser();
  }

  private static class RootElementHandler extends DefaultHandler {

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      throw new RootElementFound(qName);
    }
  }

  /**
   * Stops parsing as soon as the root element is known.
   */
  private static class RootElementFound extends SAXException {

    private final String rootElement;

    RootElementFound(String rootElement) {
      super("Root element: " + rootElement);
      this.rootElement = rootElement;
    }
  }
}
