package aQute.bnd.maven.export.plugin;

import static org.apache.maven.plugins.annotations.LifecyclePhase.PACKAGE;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.osgi.service.resolver.ResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import aQute.bnd.build.Workspace;
import aQute.bnd.exporter.executable.ExecutableJarExporter;
import aQute.bnd.exporter.runbundles.RunbundlesExporter;
import aQute.bnd.maven.lib.resolve.DependencyResolver;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.JarResource;
import aQute.bnd.osgi.Resource;
import aQute.bnd.repository.fileset.FileSetRepository;
import aQute.bnd.service.RepositoryPlugin;
import aQute.lib.io.IO;
import biz.aQute.resolve.Bndrun;
import biz.aQute.resolve.ResolveProcess;

@Mojo(name = "export", defaultPhase = PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ExportMojo extends AbstractMojo {
	private static final Logger	logger	= LoggerFactory.getLogger(ExportMojo.class);

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject				project;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
	private RepositorySystemSession		repositorySession;

	@Parameter(readonly = true, required = true)
	private List<File>	bndruns;

	@Parameter(defaultValue = "${project.build.directory}", readonly = true)
	private File		targetDir;

	@Parameter(readonly = true, required = false)
	private List<File>					bundles;

	@Parameter(defaultValue = "true")
	private boolean						useMavenDependencies;

	@Parameter(defaultValue = "false")
	private boolean				resolve;

	@Parameter(defaultValue = "true")
	private boolean				failOnChanges;

	@Parameter(defaultValue = "false")
	private boolean				bundlesOnly;

	@Parameter(defaultValue = "true")
	private boolean						attach;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession session;

	private int					errors	= 0;

	@Component
	private RepositorySystem			system;

	@Component
	private ProjectDependenciesResolver	resolver;

	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			DependencyResolver dependencyResolver = new DependencyResolver(project, repositorySession, resolver,
					system);

			FileSetRepository fileSetRepository = dependencyResolver.getFileSetRepository(project.getName(), bundles,
					useMavenDependencies);

			for (File runFile : bndruns) {
				export(runFile, fileSetRepository);
			}
		} catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}

		if (errors > 0)
			throw new MojoExecutionException(errors + " errors found");
	}

	private void export(File runFile, FileSetRepository fileSetRepository) throws Exception {
		if (!runFile.exists()) {
			logger.error("Could not find bnd run file {}", runFile);
			errors++;
			return;
		}
		String bndrun = getNamePart(runFile);
		File temporaryDir = new File(targetDir, "tmp/export/" + bndrun);
		File cnf = new File(temporaryDir, Workspace.CNFDIR);
		IO.mkdirs(cnf);
		try (Bndrun run = Bndrun.createBndrun(null, runFile)) {
			run.setBase(temporaryDir);
			Workspace workspace = run.getWorkspace();
			workspace.setBuildDir(cnf);
			workspace.setOffline(session.getSettings().isOffline());
			workspace.addBasicPlugin(fileSetRepository);
			for (RepositoryPlugin repo : workspace.getRepositories()) {
				repo.list(null);
			}
			run.getInfo(workspace);
			report(run);
			if (!run.isOk()) {
				return;
			}
			if (resolve) {
				try {
					String runBundles = run.resolve(failOnChanges, false);
					if (!run.isOk()) {
						return;
					}
					run.setProperty(Constants.RUNBUNDLES, runBundles);
				} catch (ResolutionException re) {
					logger.error("Unresolved requirements: {}", ResolveProcess.format(re.getUnresolvedRequirements()));
					throw re;
				} finally {
					report(run);
				}
			}
			try {
				Map<String, String> options = Collections.emptyMap();
				if (bundlesOnly) {
					Entry<String, Resource> export = run.export(RunbundlesExporter.RUNBUNDLES, options);
					if (export != null) {
						try (JarResource r = (JarResource) export.getValue()) {
							File runbundlesDir = new File(targetDir, "export/" + bndrun);
							r.getJar()
								.writeFolder(runbundlesDir);
						}
					}
				} else {
					Entry<String, Resource> export = run.export(ExecutableJarExporter.EXECUTABLE_JAR, options);
					if (export != null) {
						try (JarResource r = (JarResource) export.getValue()) {
							File executableJar = new File(targetDir, bndrun + ".jar");
							r.getJar()
								.write(executableJar);
							attach(executableJar, bndrun);
						}
					}
				}
			} finally {
				report(run);
			}
		}
	}

	private String getNamePart(File runFile) {
		String nameExt = runFile.getName();
		int pos = nameExt.lastIndexOf('.');
		return (pos > 0) ? nameExt.substring(0, pos) : nameExt;
	}

	private void report(Bndrun run) {
		for (String warning : run.getWarnings()) {
			logger.warn("Warning : {}", warning);
		}
		for (String error : run.getErrors()) {
			logger.error("Error   : {}", error);
			errors++;
		}
	}

	private void attach(File file, String classifier) {
		if (!attach) {
			logger.debug(
					"The export plugin has been configured not to attach the generated application to the project.");
			return;
		} else if (bundlesOnly) {
			logger.debug("The export plugin will not attach a bundles-only output to the project.");
			return;
		}

		DefaultArtifactHandler handler = new DefaultArtifactHandler("jar");
		handler.setExtension("jar");
		DefaultArtifact artifact = new DefaultArtifact(project.getGroupId(), project.getArtifactId(),
				project.getVersion(), null, "jar", classifier, handler);
		artifact.setFile(file);
		project.addAttachedArtifact(artifact);
	}

}
