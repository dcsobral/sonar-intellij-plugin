package org.intellij.sonar.analysis;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import org.intellij.sonar.console.SonarConsole;
import org.intellij.sonar.index.IssuesByFileIndexer;
import org.intellij.sonar.index.SonarIssue;
import org.intellij.sonar.persistence.IssuesByFileIndexProjectComponent;
import org.intellij.sonar.persistence.Settings;
import org.intellij.sonar.persistence.SonarServerConfig;
import org.intellij.sonar.persistence.SonarServers;
import org.intellij.sonar.sonarserver.SonarServer;
import org.intellij.sonar.util.DurationUtil;
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
    private final SonarConsole sonarConsole;

    public DownloadIssuesTask(
            SonarQubeInspectionContext.EnrichedSettings enrichedSettings,
            SonarServerConfig sonarServerConfig,
            Set<String> resourceKeys,
            ImmutableList<PsiFile> psiFiles) {

        this.enrichedSettings = enrichedSettings;
        this.sonarServerConfig = sonarServerConfig;
        this.resourceKeys = resourceKeys;
        this.psiFiles = psiFiles;
        this.sonarConsole = SonarConsole.get(enrichedSettings.project);
    }

    public static Optional<DownloadIssuesTask> from(
            SonarQubeInspectionContext.EnrichedSettings enrichedSettings,
            ImmutableList<PsiFile> psiFiles) {
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

    @Override
    public void run() {
        final SonarServer sonarServer = SonarServer.create(sonarServerConfig);
        final long startTime = System.currentTimeMillis();
        for (String resourceKey : resourceKeys) {
            final String downloadingIssuesMessage = String.format("Downloading issues for SonarQube resource %s", resourceKey);
            sonarConsole.info(downloadingIssuesMessage);
            final ImmutableList<Issue> issues = sonarServer.getAllIssuesFor(resourceKey);
            downloadedIssuesByResourceKey.put(resourceKey, issues);
        }
        onSuccess(startTime);
    }

    private void onSuccess(long downloadStartTime) {
        final int downloadedIssuesCount = FluentIterable.from(downloadedIssuesByResourceKey.values())
                .transformAndConcat(new Function<ImmutableList<Issue>, Iterable<Issue>>() {
                    @Override
                    public Iterable<Issue> apply(ImmutableList<Issue> issues) {
                        return issues;
                    }
                }).size();
        sonarConsole.info(String.format("Downloaded %d issues in %s",
                downloadedIssuesCount,
                DurationUtil.getDurationBreakdown(System.currentTimeMillis() - downloadStartTime)));

        for (Map.Entry<String, ImmutableList<Issue>> entry : downloadedIssuesByResourceKey.entrySet()) {
            if (ProgressManager.getInstance().getProgressIndicator().isCanceled()) break;
            sonarConsole.info(String.format("Creating index for SonarQube resource %s", entry.getKey()));
            long indexCreationStartTime = System.currentTimeMillis();
            final ImmutableList<Issue> issues = entry.getValue();
            final Map<String, Set<SonarIssue>> index = new IssuesByFileIndexer(psiFiles)
                    .withSonarServerIssues(issues)
                    .withSonarConsole(sonarConsole)
                    .create();
            final Optional<IssuesByFileIndexProjectComponent> indexComponent =
                    IssuesByFileIndexProjectComponent.getInstance(enrichedSettings.project);

            if (indexComponent.isPresent()) {
                indexComponent.get().getIndex().putAll(index);
            }
            final int issuesCountInIndex = FluentIterable.from(index.values()).transformAndConcat(new Function<Set<SonarIssue>, Iterable<SonarIssue>>() {
                @Override
                public Iterable<SonarIssue> apply(Set<SonarIssue> sonarIssues) {
                    return sonarIssues;
                }
            }).size();
            sonarConsole.info(String.format(
                    "Finished creating index with %d issues for SonarQube resource %s in %s",
                    issuesCountInIndex,
                    entry.getKey(),
                    DurationUtil.getDurationBreakdown(System.currentTimeMillis() - indexCreationStartTime)));
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
