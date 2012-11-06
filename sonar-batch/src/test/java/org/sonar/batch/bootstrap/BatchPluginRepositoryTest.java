/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2008-2012 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.batch.bootstrap;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.codehaus.plexus.util.FileUtils;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.core.plugins.RemotePlugin;
import org.sonar.test.TestUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BatchPluginRepositoryTest {

  private BatchPluginRepository repository;

  @After
  public void tearDown() {
    if (repository != null) {
      repository.stop();
    }
  }

  @Test
  public void shouldLoadPlugin() throws IOException {
    RemotePlugin checkstyle = new RemotePlugin("checkstyle", true);

    PluginDownloader downloader = mock(PluginDownloader.class);
    when(downloader.downloadPlugin(checkstyle)).thenReturn(copyFiles("sonar-checkstyle-plugin-2.8.jar"));

    repository = new BatchPluginRepository(downloader, new Settings());

    repository.doStart(Arrays.asList(checkstyle));

    assertThat(repository.getPlugin("checkstyle"), not(nullValue()));
    assertThat(repository.getMetadata().size(), Matchers.is(1));
    assertThat(repository.getMetadata("checkstyle").getName(), Matchers.is("Checkstyle"));
    assertThat(repository.getMetadata("checkstyle").getDeployedFiles().size(), Matchers.is(4)); // plugin + 3 dependencies
  }

  @Test
  public void shouldLoadPluginExtension() throws IOException {
    RemotePlugin checkstyle = new RemotePlugin("checkstyle", true);
    RemotePlugin checkstyleExt = new RemotePlugin("checkstyleextensions", false);

    PluginDownloader downloader = mock(PluginDownloader.class);
    when(downloader.downloadPlugin(checkstyle)).thenReturn(copyFiles("sonar-checkstyle-plugin-2.8.jar"));
    when(downloader.downloadPlugin(checkstyleExt)).thenReturn(copyFiles("sonar-checkstyle-extensions-plugin-0.1-SNAPSHOT.jar"));

    repository = new BatchPluginRepository(downloader, new Settings());

    repository.doStart(Arrays.asList(checkstyle, checkstyleExt));

    assertThat(repository.getPlugin("checkstyle"), not(nullValue()));
    assertThat(repository.getPlugin("checkstyleextensions"), not(nullValue()));
    assertThat(repository.getMetadata().size(), Matchers.is(2));
    assertThat(repository.getMetadata("checkstyle").getName(), Matchers.is("Checkstyle"));
    assertThat(repository.getMetadata("checkstyleextensions").getVersion(), Matchers.is("0.1-SNAPSHOT"));
  }

  @Test
  public void shouldLoadPluginDeprecatedExtensions() throws IOException {
    RemotePlugin checkstyle = new RemotePlugin("checkstyle", true)
      .addFilename("checkstyle-ext.xml");

    PluginDownloader downloader = mock(PluginDownloader.class);
    when(downloader.downloadPlugin(checkstyle)).thenReturn(copyFiles("sonar-checkstyle-plugin-2.8.jar", "checkstyle-ext.xml"));

    repository = new BatchPluginRepository(downloader, new Settings());

    repository.doStart(Arrays.asList(checkstyle));

    assertThat(repository.getPlugin("checkstyle"), not(nullValue()));
    assertThat(repository.getMetadata().size(), Matchers.is(1));
    assertThat(repository.getMetadata("checkstyle").getName(), Matchers.is("Checkstyle"));
    assertThat(repository.getMetadata("checkstyle").getDeployedFiles().size(), Matchers.is(5)); // plugin + 3 dependencies + 1 deprecated
    // extension
  }

  @Test
  public void shouldExcludePluginAndItsExtensions() throws IOException {
    RemotePlugin checkstyle = new RemotePlugin("checkstyle", true);
    RemotePlugin checkstyleExt = new RemotePlugin("checkstyleextensions", false);

    PluginDownloader downloader = mock(PluginDownloader.class);
    when(downloader.downloadPlugin(checkstyle)).thenReturn(copyFiles("sonar-checkstyle-plugin-2.8.jar"));
    when(downloader.downloadPlugin(checkstyleExt)).thenReturn(copyFiles("sonar-checkstyle-extensions-plugin-0.1-SNAPSHOT.jar"));

    Settings settings = new Settings();
    settings.setProperty(CoreProperties.BATCH_EXCLUDE_PLUGINS, "checkstyle");
    repository = new BatchPluginRepository(downloader, settings);

    repository.doStart(Arrays.asList(checkstyle, checkstyleExt));

    assertThat(repository.getMetadata().size(), Matchers.is(0));
  }

  private List<File> copyFiles(String... filenames) throws IOException {
    List<File> files = Lists.newArrayList();
    for (String filename : filenames) {
      File file = TestUtils.getResource("/org/sonar/batch/bootstrap/BatchPluginRepositoryTest/" + filename);
      File tempDir = new File("target/test-tmp/BatchPluginRepositoryTest");
      FileUtils.forceMkdir(tempDir);
      FileUtils.copyFileToDirectory(file, tempDir);
      files.add(new File(tempDir, filename));
    }
    return files;
  }

  @Test
  public void shouldAlwaysAcceptIfNoWhiteListAndBlackList() {
    repository = new BatchPluginRepository(mock(PluginDownloader.class), new Settings());
    assertThat(repository.isAccepted("pmd", null, null), Matchers.is(true));
  }

  @Test
  public void whiteListShouldTakePrecedenceOverBlackList() {
    Set<String> whiteList = Sets.newHashSet("checkstyle", "pmd", "findbugs");
    Set<String> blackList = Sets.newHashSet("cobertura", "pmd");
    assertThat(BatchPluginRepository.isAccepted("pmd", whiteList, blackList), Matchers.is(true));
  }

  @Test
  public void corePluginShouldAlwaysBeInWhiteList() {
    Set<String> whiteList = Sets.newHashSet("checkstyle", "pmd", "findbugs");
    Set<String> blackList = null;

    assertThat(BatchPluginRepository.isAccepted("core", whiteList, blackList), Matchers.is(true));
  }

  @Test
  public void corePluginShouldNeverBeInBlackList() {
    Set<String> whiteList = null;
    Set<String> blackList = Sets.newHashSet("core", "findbugs");

    assertThat(BatchPluginRepository.isAccepted("core", whiteList, blackList), Matchers.is(true));
  }

  // English Pack plugin should never be blacklisted as it is mandatory for the I18nManager on batch side
  @Test
  public void englishPackPluginShouldNeverBeInBlackList() {
    Set<String> whiteList = null;
    Set<String> blackList = Sets.newHashSet("l10nen", "findbugs");
    assertThat(BatchPluginRepository.isAccepted("l10nen", whiteList, blackList), Matchers.is(true));
  }

  @Test
  public void shouldCheckWhitelist() {
    Set<String> whiteList = Sets.newHashSet("checkstyle", "pmd", "findbugs");
    Set<String> blackList = null;

    assertThat(BatchPluginRepository.isAccepted("checkstyle", whiteList, blackList), Matchers.is(true));
    assertThat(BatchPluginRepository.isAccepted("pmd", whiteList, blackList), Matchers.is(true));
    assertThat(BatchPluginRepository.isAccepted("cobertura", whiteList, blackList), Matchers.is(false));
  }

  @Test
  public void shouldCheckBlackListIfNoWhiteList() {
    Set<String> whiteList = null;
    Set<String> blackList = Sets.newHashSet("checkstyle", "pmd", "findbugs");
    assertThat(BatchPluginRepository.isAccepted("checkstyle", whiteList, blackList), Matchers.is(false));
    assertThat(BatchPluginRepository.isAccepted("pmd", whiteList, blackList), Matchers.is(false));
    assertThat(BatchPluginRepository.isAccepted("cobertura", whiteList, blackList), Matchers.is(true));
  }

}
