/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.batch.report;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.test.MutableTestCase;
import org.sonar.api.test.MutableTestPlan;
import org.sonar.api.test.TestCase;
import org.sonar.batch.index.BatchResource;
import org.sonar.batch.index.ResourceCache;
import org.sonar.batch.protocol.Constants.TestStatus;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReport.Test;
import org.sonar.batch.protocol.output.BatchReportWriter;
import org.sonar.core.test.TestPlanBuilder;

public class TestExecutionPublisher implements ReportPublisherStep {

  private final ResourceCache resourceCache;
  private final TestPlanBuilder testPlanBuilder;

  public TestExecutionPublisher(ResourceCache resourceCache, TestPlanBuilder testPlanBuilder) {
    this.resourceCache = resourceCache;
    this.testPlanBuilder = testPlanBuilder;
  }

  @Override
  public void publish(BatchReportWriter writer) {
    for (final BatchResource resource : resourceCache.all()) {
      if (!resource.isFile()) {
        continue;
      }

      DefaultInputFile inputFile = (DefaultInputFile) resource.inputPath();
      if (inputFile.type() != Type.TEST) {
        continue;
      }

      MutableTestPlan testPlan = testPlanBuilder.get(MutableTestPlan.class, inputFile.key());
      if (testPlan == null || Iterables.isEmpty(testPlan.testCases())) {
        continue;
      }

      writer.writeTests(resource.batchId(), Iterables.transform(testPlan.testCases(), new Function<MutableTestCase, BatchReport.Test>() {

        private BatchReport.Test.Builder builder = BatchReport.Test.newBuilder();

        @Override
        public Test apply(MutableTestCase input) {
          builder.clear();
          builder.setName(input.name());
          Long durationInMs = input.durationInMs();
          if (durationInMs != null) {
            builder.setDurationInMs(durationInMs);
          }
          String msg = input.message();
          if (msg != null) {
            builder.setMsg(msg);
          }
          String stack = input.stackTrace();
          if (stack != null) {
            builder.setStacktrace(stack);
          }
          TestCase.Status status = input.status();
          if (status != null) {
            builder.setStatus(TestStatus.valueOf(status.name()));
          }
          return builder.build();
        }

      }));
    }
  }
}
