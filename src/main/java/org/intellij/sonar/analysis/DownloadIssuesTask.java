package org.intellij.sonar.analysis;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.intellij.psi.PsiFile;
import org.intellij.sonar.index2.IssuesByFileIndexer;
import org.intellij.sonar.index2.SonarIssue;
import org.intellij.sonar.persistence.IssuesByFileIndexProjectComponent;
import org.intellij.sonar.persistence.Settings;
import org.intellij.sonar.persistence.SonarServerConfig;
import org.intellij.sonar.persistence.SonarServers;
import org.intellij.sonar.sonarserver.SonarServer;
import org.intellij.sonar.util.SettingsUtil;
import org.sonar.wsclient.issue.Issue;
import org.sonar.wsclient.services.Resource;

import java.util.Map;
import java.util.Set;

public class DownloadIssuesTask implements Runnable {
  private final SonarServerConfig sonarServerConfig;
  private final Set<String> resourceKeys;
  private final ImmutableList<PsiFile> psiFiles;
  private final Map<String, ImmutableList<Issue>> downloadedIssuesByResourceKey = Maps.newConcurrentMap();
  private final SonarQubeInspectionContext.EnrichedSettings enrichedSettings;

  public static Optional<DownloadIssuesTask> from(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, ImmutableList<PsiFile> psiFiles) {
    final Settings settings = SettingsUtil.process(enrichedSettings.project, enrichedSettings.settings);
    final Optional<SonarServerConfig> c = SonarServers.get(settings.getServerName());
    if (!c.isPresent()) return Optional.absent();
    final ImmutableSet<String> resourceKeys = FluentIterable.from(settings.getResources()).
        transform(new Function<Resource, String>() {
          @Override
          public String apply(Resource resource) {
            return resource.getKey();
          }
        }).toSet();

    return Optional.of(new DownloadIssuesTask(
        enrichedSettings,
        c.get(),
        resourceKeys, psiFiles));
  }

  public DownloadIssuesTask(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, SonarServerConfig sonarServerConfig, Set<String> resourceKeys, ImmutableList<PsiFile> psiFiles) {
    this.enrichedSettings = enrichedSettings;
    this.sonarServerConfig = sonarServerConfig;
    this.resourceKeys = resourceKeys;
    this.psiFiles = psiFiles;
  }

  @Override
  public void run() {
    final SonarServer sonarServer = SonarServer.create(sonarServerConfig);
    for (String resourceKey : resourceKeys) {
      final ImmutableList<Issue> issues = sonarServer.getAllIssuesFor(resourceKey);
      downloadedIssuesByResourceKey.put(resourceKey, issues);
    }
    onSuccess();
  }

  public void onSuccess() {
    for (Map.Entry<String, ImmutableList<Issue>> entry : downloadedIssuesByResourceKey.entrySet()) {
      final String resourceKey = entry.getKey();
      final ImmutableList<Issue> issues = entry.getValue();
      final Map<String, Set<SonarIssue>> index = new IssuesByFileIndexer(psiFiles, resourceKey).withSonarServerIssues(issues).create();
      final Optional<IssuesByFileIndexProjectComponent> indexComponent = IssuesByFileIndexProjectComponent.getInstance(enrichedSettings.project);
      if (indexComponent.isPresent()) {
        indexComponent.get().setIndex(index);
      }
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("sonarServerConfig", sonarServerConfig)
        .add("resourceKeys", resourceKeys)
        .add("psiFiles", psiFiles)
        .add("downloadedIssuesByResourceKey", downloadedIssuesByResourceKey)
        .toString();
  }

}