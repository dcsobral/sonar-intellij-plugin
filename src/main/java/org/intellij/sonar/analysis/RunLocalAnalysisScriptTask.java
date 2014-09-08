package org.intellij.sonar.analysis;

import com.google.common.base.*;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import org.intellij.sonar.console.SonarConsole;
import org.intellij.sonar.console.StreamGobbler;
import org.intellij.sonar.index2.IssuesByFileIndexer;
import org.intellij.sonar.index2.SonarIssue;
import org.intellij.sonar.persistence.*;
import org.intellij.sonar.sonarreport.data.SonarReport;
import org.intellij.sonar.util.SettingsUtil;
import org.intellij.sonar.util.TemplateProcessor;
import org.sonar.wsclient.services.Resource;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.intellij.sonar.console.ConsoleLogLevel.ERROR;
import static org.intellij.sonar.console.ConsoleLogLevel.INFO;

public class RunLocalAnalysisScriptTask implements Runnable {
  private final String sourceCode;
  private final String pathToSonarReport;
  private final SonarQubeInspectionContext.EnrichedSettings enrichedSettings;
  private final File workingDir;
  private final SonarConsole sonarConsole;
  private final ImmutableList<PsiFile> psiFiles;

  public static Optional<RunLocalAnalysisScriptTask> from(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, ImmutableList<PsiFile> psiFiles) {
    enrichedSettings.settings = SettingsUtil.process(enrichedSettings.project, enrichedSettings.settings);
    final String scripName = enrichedSettings.settings.getLocalAnalysisScripName();
    final Optional<LocalAnalysisScript> localAnalysisScript = LocalAnalysisScripts.get(scripName);
    if (!localAnalysisScript.isPresent()) return Optional.absent();
    final String sourceCodeTemplate = localAnalysisScript.get().getSourceCode();
    final String serverName = enrichedSettings.settings.getServerName();
    final Optional<SonarServerConfig> serverConfiguration = SonarServers.get(serverName);

    final TemplateProcessor sourceCodeTemplateProcessor = TemplateProcessor.of(sourceCodeTemplate);
    sourceCodeTemplateProcessor
        .withProject(enrichedSettings.project)
        .withModule(enrichedSettings.module);

    String pathToSonarReportTemplate = localAnalysisScript.get().getPathToSonarReport();
    final TemplateProcessor pathToSonarReportTemplateProcessor = TemplateProcessor.of(pathToSonarReportTemplate)
        .withProject(enrichedSettings.project)
        .withModule(enrichedSettings.module);

    if (serverConfiguration.isPresent()) {
      sourceCodeTemplateProcessor.withSonarServerConfiguration(serverConfiguration.get());
      pathToSonarReportTemplateProcessor.withSonarServerConfiguration(serverConfiguration.get());
    }

    final String sourceCode = sourceCodeTemplateProcessor.process();
    final String pathToSonarReport = pathToSonarReportTemplateProcessor.process();

    File workingDir;
    if (enrichedSettings.module != null && enrichedSettings.module.getModuleFile() != null) {
      workingDir = new File(enrichedSettings.module.getModuleFile().getParent().getPath());
    } else {
      workingDir = new File(enrichedSettings.project.getBasePath());
    }

    return Optional.of(new RunLocalAnalysisScriptTask(
        enrichedSettings, sourceCode, pathToSonarReport, workingDir,
        psiFiles));
  }

  public RunLocalAnalysisScriptTask(SonarQubeInspectionContext.EnrichedSettings enrichedSettings, String sourceCode, String pathToSonarReport, File workingDir, ImmutableList<PsiFile> psiFiles) {
    this.enrichedSettings = enrichedSettings;
    this.sourceCode = sourceCode;
    this.pathToSonarReport = pathToSonarReport;
    this.workingDir = workingDir;
    this.psiFiles = psiFiles;
    this.sonarConsole = SonarConsole.get(enrichedSettings.project);
  }

  public void run() {
    // execute local analysis script

//    script execution needs
//        working dir
//        PROJECT or MODULE dir
//    $WORKING_DIR$ = $PROJECT_DIR$ or $MODULE_DIR$/..
//    mvn sonar:sonar
//        -DskipTests=true
//        -Dsonar.language=java
//        -Dsonar.analysis.mode=incremental
//        -Dsonar.issuesReport.html.enable=true
//        -Dsonar.host.url=$SONAR_HOST_URL$=https://sonar.corp.mobile.de/sonar -pl $MODULE_DIR_NAME$=dealer-admin

//    /Users/omayevskiy/mobile_workspace/mobile-platform/dealer-admin/target/sonar/sonar-report.json
//    $MODULE_DIR$/target/sonar/sonar-report.json
//    $PROJECT_DIR$/target/sonar/sonar-report.json
//    $WORKING_DIR$/target/sonar/sonar-report.json

//    $WORKING_DIR$ = $MODULE_DIR$
//    sonar-runner -Dsonar.analysis.mode=incremental -Dsonar.host.url=$SONAR_HOST_URL$=$https://sonar.corp.mobile.de/sonar
//    /Users/omayevskiy/mobile_workspace/mobile-platform/mobile-static-resources/.sonar/sonar-report.json
//    $MODULE_DIR$/.sonar-report.json
//    $WORKING_DIR$/.sonar/sonar-report.json

    sonarConsole.info("working dir: " + this.workingDir.getPath());
    sonarConsole.info("run: " + this.sourceCode);
    sonarConsole.info("report: " + this.pathToSonarReport);

    final Stopwatch stopwatch = new Stopwatch().start();

    final Process process;
    try {
      process = Runtime.getRuntime().exec(this.sourceCode.split("[\\s]+"), null, this.workingDir);
    } catch (IOException e) {
      sonarConsole.error(Throwables.getStackTraceAsString(e));
      return;
    }
    final StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), sonarConsole, ERROR);
    final StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), sonarConsole, INFO);
    errorGobbler.start();
    outputGobbler.start();

    final ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    indicator.setIndeterminate(true);

    while (outputGobbler.isAlive()) {
      if (indicator.isCanceled()) {
        process.destroy();
        break;
      }
    }

    int exitCode = process.exitValue();

    sonarConsole.info(String.format("finished with exit code %s in %d ms", exitCode, stopwatch.stop().elapsed(TimeUnit.MILLISECONDS)));

    readIssuesFromSonarReport();

  }

  private void readIssuesFromSonarReport() {
    String sonarReportContent = null;
    try {
      sonarReportContent = Files.toString(new File(pathToSonarReport), Charsets.UTF_8);
    } catch (IOException e) {
      sonarConsole.info(Throwables.getStackTraceAsString(e));
      return;
    }
    final SonarReport sonarReport = SonarReport.fromJson(sonarReportContent);

    for (Resource resource : enrichedSettings.settings.getResources()) {
      final Map<String, Set<SonarIssue>> index = new IssuesByFileIndexer(psiFiles, resource.getKey())
          .withSonarReportIssues(sonarReport.getIssues())
          .create();
      final Optional<IssuesByFileIndexProjectComponent> indexComponent = IssuesByFileIndexProjectComponent.getInstance(enrichedSettings.project);
      if (indexComponent.isPresent() && !index.isEmpty()) {
        indexComponent.get().getIndex().putAll(index);
      }
    }
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("sourceCode", sourceCode)
        .add("pathToSonarReport", pathToSonarReport)
        .toString();
  }
}