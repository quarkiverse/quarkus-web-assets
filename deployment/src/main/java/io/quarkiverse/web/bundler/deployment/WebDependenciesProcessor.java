package io.quarkiverse.web.bundler.deployment;

import static io.mvnpm.esbuild.model.WebDependency.WebDependencyType.resolveType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.jboss.logging.Logger;

import io.mvnpm.esbuild.install.WebDepsInstaller;
import io.mvnpm.esbuild.model.BundleOptions;
import io.mvnpm.esbuild.model.WebDependency;
import io.mvnpm.esbuild.model.WebDependency.WebDependencyType;
import io.quarkiverse.web.bundler.deployment.items.EntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.InstalledWebDependenciesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectRootBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebDependenciesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebDependenciesBuildItem.Dependency;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.maven.dependency.DependencyFlags;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.runtime.configuration.ConfigurationException;

class WebDependenciesProcessor {

    private static final Logger LOGGER = Logger.getLogger(WebDependenciesProcessor.class);

    @BuildStep
    WebDependenciesBuildItem collectDependencies(LaunchModeBuildItem launchMode,
            CurateOutcomeBuildItem curateOutcome,
            List<EntryPointBuildItem> entryPoints,
            WebBundlerConfig config) {
        if (entryPoints.isEmpty() && !config.dependencies().autoImport().isEnabled()) {
            return new WebDependenciesBuildItem(List.of());
        }
        final List<Dependency> dependencies = StreamSupport.stream(curateOutcome.getApplicationModel()
                .getDependenciesWithAnyFlag(DependencyFlags.COMPILE_ONLY, DependencyFlags.RUNTIME_CP).spliterator(), false)
                .filter(io.quarkus.maven.dependency.Dependency::isJar)
                .filter(d -> WebDependencyType.anyMatch(d.toCompactCoords()))
                .peek(d -> checkScope(launchMode, d, config))
                .map(WebDependenciesProcessor::toWebDep)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return new WebDependenciesBuildItem(dependencies);
    }

    @BuildStep
    InstalledWebDependenciesBuildItem installDependencies(LaunchModeBuildItem launchMode,
            LiveReloadBuildItem liveReload,
            OutputTargetBuildItem outputTarget,
            ProjectRootBuildItem projectRoot,
            WebDependenciesBuildItem webDependencies,
            WebBundlerConfig config) {
        final InstalledWebDependenciesContext installedWebDependenciesContext = liveReload
                .getContextObject(InstalledWebDependenciesContext.class);
        final Path nodeModulesDir = resolveNodeModulesDir(config, outputTarget, projectRoot);
        if (liveReload.isLiveReload() && installedWebDependenciesContext != null
                && WebBundlerConfig.WebDependenciesConfig.isEqual(config.dependencies(),
                        installedWebDependenciesContext.config().dependencies())
                && nodeModulesDir.equals(installedWebDependenciesContext.nodeModulesDir())
                && installedWebDependenciesContext.dependencies().equals(webDependencies.list())) {
            return new InstalledWebDependenciesBuildItem(nodeModulesDir, webDependencies.list());
        }
        long startedInstall = Instant.now().toEpochMilli();
        try {
            final List<WebDependency> toInstall = webDependencies.toEsBuildWebDependencies();
            if (WebDepsInstaller.install(nodeModulesDir, toInstall)) {
                final long duration = Instant.now().minusMillis(startedInstall).toEpochMilli();
                if (LOGGER.isDebugEnabled()) {
                    String deps = webDependencies.list().stream().map(Dependency::id)
                            .collect(
                                    Collectors.joining(", "));
                    LOGGER.infof("%d web dependencies installed in %sms: %s", webDependencies.list().size(),
                            duration, deps);
                } else {
                    LOGGER.infof("%d web Dependencies installed in %sms.", webDependencies.list().size(),
                            duration);
                }
            } else if (webDependencies.isEmpty()) {
                LOGGER.info("No web dependencies to install.");
            } else {
                LOGGER.info("All web dependencies are already installed.");
            }
            liveReload.setContextObject(InstalledWebDependenciesContext.class,
                    new InstalledWebDependenciesContext(config, nodeModulesDir, webDependencies.list()));
            return new InstalledWebDependenciesBuildItem(nodeModulesDir, webDependencies.list());
        } catch (IOException e) {
            liveReload.setContextObject(InstalledWebDependenciesContext.class, new InstalledWebDependenciesContext());
            throw new RuntimeException(e);
        }
    }

    private void checkScope(LaunchModeBuildItem launchMode, ResolvedDependency d, WebBundlerConfig config) {
        if (!launchMode.getLaunchMode().isDevOrTest() && config.dependencies().compileOnly() && d.isRuntimeCp()) {
            throw new ConfigurationException(
                    ("The Web Bundler is configured to only include compileOnly web dependencies, but %s is set as runtime." +
                            " Use a compile only scope (e.g. provided) or set quarkus.web-bundler.dependencies.compile-only=false to allow runtime web dependencies.")
                            .formatted(d.toCompactCoords()));
        }
    }

    private static Dependency toWebDep(ResolvedDependency d) {
        return d.getResolvedPaths().stream().filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst()
                .map(j -> new Dependency(d, d.toCompactCoords(), j, resolveType(d.toCompactCoords()).orElseThrow(),
                        d.isDirect()))
                .orElse(null);
    }

    private static Path resolveNodeModulesDir(WebBundlerConfig config, OutputTargetBuildItem outputTarget,
            ProjectRootBuildItem projectRoot) {
        if (config.dependencies().nodeModules().isEmpty()) {
            return outputTarget.getOutputDirectory().resolve(BundleOptions.NODE_MODULES);
        }
        final Path nodeModulesDir = Path.of(config.dependencies().nodeModules().get().trim());
        if (nodeModulesDir.isAbsolute() && Files.isDirectory(nodeModulesDir.getParent())) {
            return nodeModulesDir;
        }
        if (!projectRoot.exists() || !Files.isDirectory(projectRoot.path())) {
            throw new IllegalStateException(
                    "If not absolute, the node_modules directory is resolved relative to the project root, but Web Bundler was not able to find the project root.");
        }
        return projectRoot.path().resolve(nodeModulesDir);
    }

    record InstalledWebDependenciesContext(WebBundlerConfig config, Path nodeModulesDir, List<Dependency> dependencies) {
        InstalledWebDependenciesContext() {
            this(null, null, List.of());
        }
    }

}
