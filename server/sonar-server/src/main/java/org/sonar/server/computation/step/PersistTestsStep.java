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

package org.sonar.server.computation.step;

import com.google.common.base.Joiner;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.internal.Uuids;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.batch.protocol.output.BatchReportReader;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.source.db.FileSourceDto;
import org.sonar.core.source.db.FileSourceDto.Type;
import org.sonar.server.computation.ComputationContext;
import org.sonar.server.computation.source.ReportIterator;
import org.sonar.server.db.DbClient;
import org.sonar.server.source.db.FileSourceDb;

import javax.annotation.CheckForNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class PersistTestsStep implements ComputationStep {

  private static final Logger LOG = Loggers.get(PersistTestsStep.class);

  private final DbClient dbClient;
  private final System2 system;

  public PersistTestsStep(DbClient dbClient, System2 system) {
    this.dbClient = dbClient;
    this.system = system;
  }

  @Override
  public String[] supportedProjectQualifiers() {
    return new String[] {Qualifiers.PROJECT};
  }

  @Override
  public void execute(ComputationContext computationContext) {
    DbSession session = dbClient.openSession(true);
    try {
      int rootComponentRef = computationContext.getReportMetadata().getRootComponentRef();
      TestContext context = new TestContext(computationContext, session);

      recursivelyProcessComponent(context, rootComponentRef);
      session.commit();
      if (context.hasUnprocessedCoverageDetails) {
        LOG.warn("Some coverage tests are not taken into account during analysis of project '{}'", computationContext.getProject().getKey());
      }
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private void recursivelyProcessComponent(TestContext context, int componentRef) {
    BatchReportReader reportReader = context.reader;
    BatchReport.Component component = reportReader.readComponent(componentRef);
    if (component.getIsTest()) {
      persistTestResults(component, context);
    }

    for (Integer childRef : component.getChildRefList()) {
      recursivelyProcessComponent(context, childRef);
    }
  }

  private void persistTestResults(BatchReport.Component component, TestContext context) {
    Multimap<String, FileSourceDb.Test.Builder> testsByName = buildDbTests(context, component);
    ListMultimap<String, FileSourceDb.Test.CoveredFile> coveredFilesByName = loadCoverageDetails(component.getRef(), context);
    List<FileSourceDb.Test> tests = addCoveredFilesToTests(testsByName, coveredFilesByName);
    if (hasUnprocessedCoverageDetails(testsByName, coveredFilesByName, component)) {
      context.hasUnprocessedCoverageDetails = true;
    }

    FileSourceDto existingDto = context.existingFileSourcesByUuid.get(component.getUuid());
    long now = system.now();
    if (existingDto != null) {
      // update
      existingDto
        .setTestData(tests)
        .setUpdatedAt(now);
      dbClient.fileSourceDao().update(context.session, existingDto);
    } else {
      // insert
      FileSourceDto newDto = new FileSourceDto()
        .setTestData(tests)
        .setFileUuid(component.getUuid())
        .setProjectUuid(context.context.getProject().uuid())
        .setDataType(Type.TEST)
        .setCreatedAt(now)
        .setUpdatedAt(now);
      dbClient.fileSourceDao().insert(context.session, newDto);
    }
  }

  private boolean hasUnprocessedCoverageDetails(Multimap<String, FileSourceDb.Test.Builder> testsByName, ListMultimap<String, FileSourceDb.Test.CoveredFile> coveredFilesByName,
    BatchReport.Component component) {
    HashSet<String> unprocessedCoverageDetailNames = new HashSet<>(coveredFilesByName.keySet());
    boolean hasUnprocessedCoverageDetails = unprocessedCoverageDetailNames.removeAll(testsByName.keySet());
    if (hasUnprocessedCoverageDetails) {
      LOG.trace("The following test coverages for file '{}' have not been taken into account: {}", component.getPath(), Joiner.on(", ").join(unprocessedCoverageDetailNames));
    }
    return hasUnprocessedCoverageDetails;
  }

  private List<FileSourceDb.Test> addCoveredFilesToTests(Multimap<String, FileSourceDb.Test.Builder> testsByName,
    ListMultimap<String, FileSourceDb.Test.CoveredFile> coveredFilesByName) {
    List<FileSourceDb.Test> tests = new ArrayList<>();
    for (FileSourceDb.Test.Builder test : testsByName.values()) {
      List<FileSourceDb.Test.CoveredFile> coveredFiles = coveredFilesByName.get(test.getName());
      if (coveredFiles != null) {
        test.addAllCoveredFile(coveredFiles);
      }
      tests.add(test.build());
    }

    return tests;
  }

  private Multimap<String, FileSourceDb.Test.Builder> buildDbTests(TestContext context, BatchReport.Component component) {
    Multimap<String, FileSourceDb.Test.Builder> tests = ArrayListMultimap.create();
    ReportIterator<BatchReport.Test> testIterator = new ReportIterator<>(context.reader.readTests(component.getRef()), BatchReport.Test.PARSER);
    while (testIterator.hasNext()) {
      BatchReport.Test batchTest = testIterator.next();
      FileSourceDb.Test.Builder dbTest = FileSourceDb.Test.newBuilder();
      dbTest.setUuid(Uuids.create());
      dbTest.setName(batchTest.getName());
      dbTest.setType(batchTest.getType());
      if (batchTest.hasStacktrace()) {
        dbTest.setStacktrace(batchTest.getStacktrace());
      }
      if (batchTest.hasStatus()) {
        dbTest.setStatus(batchTest.getStatus());
      }
      if (batchTest.hasMsg()) {
        dbTest.setMsg(batchTest.getMsg());
      }
      if (batchTest.hasExecutionTimeMs()) {
        dbTest.setExecutionTimeMs(batchTest.getExecutionTimeMs());
      }

      tests.put(dbTest.getName(), dbTest);
    }

    return tests;
  }

  @CheckForNull
  private ListMultimap<String, FileSourceDb.Test.CoveredFile> loadCoverageDetails(int testFileRef, TestContext context) {
    File coverageDetailsFile = context.reader.readCoverageDetails(testFileRef);
    ListMultimap<String, FileSourceDb.Test.CoveredFile> nameToCoveredFiles = ArrayListMultimap.create();
    if (coverageDetailsFile == null) {
      return nameToCoveredFiles;
    }

    ReportIterator<BatchReport.CoverageDetail> coverageIterator = new ReportIterator<>(coverageDetailsFile, BatchReport.CoverageDetail.PARSER);
    while (coverageIterator.hasNext()) {
      BatchReport.CoverageDetail batchCoverageDetail = coverageIterator.next();
      for (BatchReport.CoverageDetail.CoveredFile batchCoveredFile : batchCoverageDetail.getCoveredFileList()) {
        FileSourceDb.Test.CoveredFile.Builder dbCoveredFile = FileSourceDb.Test.CoveredFile.newBuilder()
          .setFileUuid(context.getUuid(batchCoveredFile.getFileRef()))
          .addAllCoveredLine(batchCoveredFile.getCoveredLineList());
        nameToCoveredFiles.put(batchCoverageDetail.getTestName(), dbCoveredFile.build());
      }
    }
    return nameToCoveredFiles;
  }

  @Override
  public String getDescription() {
    return "Persist tests";
  }

  private static class TestContext {
    final DbSession session;
    final ComputationContext context;
    final BatchReportReader reader;
    final Cache<Integer, String> componentRefToUuidCache;
    final Map<String, FileSourceDto> existingFileSourcesByUuid;
    boolean hasUnprocessedCoverageDetails = false;

    TestContext(ComputationContext context, DbSession session) {
      this.session = session;
      this.context = context;
      this.reader = context.getReportReader();
      this.componentRefToUuidCache = CacheBuilder.newBuilder()
        .maximumSize(500_000)
        .build(
          new CacheLoader<Integer, String>() {
            public String load(Integer key) {
              return reader.readComponent(key).getUuid();
            }
          });
      existingFileSourcesByUuid = new HashMap<>();
      session.select("org.sonar.core.source.db.FileSourceMapper.selectHashesForProject",
        ImmutableMap.of("projectUuid", context.getProject().uuid(), "dataType", Type.TEST),
        new ResultHandler() {
          @Override
          public void handleResult(ResultContext context) {
            FileSourceDto dto = (FileSourceDto) context.getResultObject();
            existingFileSourcesByUuid.put(dto.getFileUuid(), dto);
          }
        });
    }

    public String getUuid(int fileRef) {
      try {
        return componentRefToUuidCache.get(fileRef);
      } catch (ExecutionException e) {
        throw new IllegalStateException(String.format("Error while retrieving uuid of component file ref '%d'", fileRef));
      }
    }
  }
}
