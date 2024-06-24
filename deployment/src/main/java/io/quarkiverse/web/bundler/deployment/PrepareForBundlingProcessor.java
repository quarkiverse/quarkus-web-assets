package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundleWebAssetsScannerProcessor.MAIN_ENTRYPOINT_KEY;
import static io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType.MANUAL;
import static io.quarkiverse.web.bundler.deployment.util.PathUtils.join;
import static io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourcesProcessor.WEB_BUNDLER_LIVE_RELOAD_PATH;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.mvnpm.esbuild.model.*;
import io.quarkiverse.web.bundler.deployment.WebBundlerConfig.LoadersConfig;
import io.quarkiverse.web.bundler.deployment.items.*;
import io.quarkiverse.web.bundler.deployment.items.WebDependenciesBuildItem.Dependency;
import io.quarkiverse.web.bundler.deployment.util.PathUtils;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;
import io.quarkus.deployment.util.FileUtil;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;

public class PrepareForBundlingProcessor {

    private static final Logger LOGGER = Logger.getLogger(PrepareForBundlingProcessor.class);

    private static final Map<EsBuildConfig.Loader, Function<LoadersConfig, Optional<Set<String>>>> LOADER_CONFIGS = Map
            .ofEntries(
                    entry(EsBuildConfig.Loader.JS, LoadersConfig::js),
                    entry(EsBuildConfig.Loader.JSX, LoadersConfig::jsx),
                    entry(EsBuildConfig.Loader.TS, LoadersConfig::ts),
                    entry(EsBuildConfig.Loader.TSX, LoadersConfig::tsx),
                    entry(EsBuildConfig.Loader.CSS, LoadersConfig::css),
                    entry(EsBuildConfig.Loader.LOCAL_CSS, LoadersConfig::localCss),
                    entry(EsBuildConfig.Loader.GLOBAL_CSS, LoadersConfig::globalCss),
                    entry(EsBuildConfig.Loader.JSON, LoadersConfig::json),
                    entry(EsBuildConfig.Loader.TEXT, LoadersConfig::text),
                    entry(EsBuildConfig.Loader.FILE, LoadersConfig::file),
                    entry(EsBuildConfig.Loader.EMPTY, LoadersConfig::empty),
                    entry(EsBuildConfig.Loader.COPY, LoadersConfig::copy),
                    entry(EsBuildConfig.Loader.DATAURL, LoadersConfig::dataUrl),
                    entry(EsBuildConfig.Loader.BASE64, LoadersConfig::base64),
                    entry(EsBuildConfig.Loader.BINARY, LoadersConfig::binary));
    public static final String TARGET_DIR_NAME = "web-bundler/";
    public static final String DIST = "dist";

    static {
        for (EsBuildConfig.Loader loader : EsBuildConfig.Loader.values()) {
            if (!LOADER_CONFIGS.containsKey(loader)) {
                throw new Error("There is no WebBundleConfig.LoadersConfig for this loader : " + loader);
            }
        }
    }

    @BuildStep
    WebBundlerTargetDirBuildItem initTargetDir(OutputTargetBuildItem outputTarget, LaunchModeBuildItem launchMode,
            LiveReloadBuildItem liveReload) {
        final String targetDirName = TARGET_DIR_NAME + launchMode.getLaunchMode().getDefaultProfile();
        final Path targetDir = outputTarget.getOutputDirectory().resolve(targetDirName);
        final Path distDir = targetDir.resolve(DIST);
        if (!liveReload.isLiveReload()) {
            try {
                FileUtil.deleteDirectory(targetDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new WebBundlerTargetDirBuildItem(targetDir, distDir);
    }

    @BuildStep
    ReadyForBundlingBuildItem prepareForBundling(WebBundlerConfig config,
            InstalledWebDependenciesBuildItem installedWebDependencies,
            List<EntryPointBuildItem> entryPoints,
            WebBundlerTargetDirBuildItem targetDir,
            Optional<BundleConfigAssetsBuildItem> bundleConfig,
            LiveReloadBuildItem liveReload,
            LaunchModeBuildItem launchMode,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            HttpBuildTimeConfig httpBuildTimeConfig,
            OutputTargetBuildItem outputTarget) {
        if (entryPoints.isEmpty()) {
            if (!config.dependencies().autoImport().isEnabled()) {
                LOGGER.warn("Skipping Web-Bundling because no entry-point detected (create one or enable auto-import)");
                return null;
            } else {
                if (installedWebDependencies.isEmpty()) {
                    LOGGER.warn("Skipping Web-Bundling because no Web Dependencies found for auto-import.");
                    return null;
                } else {
                    LOGGER.info("No entry points found, it will be generated based on direct Web Dependencies.");
                }
            }
        }

        final PrepareForBundlingContext prepareForBundlingContext = liveReload
                .getContextObject(PrepareForBundlingContext.class);
        final boolean isLiveReload = liveReload.isLiveReload()
                && prepareForBundlingContext != null;
        final long started = Instant.now().toEpochMilli();
        if (isLiveReload
                && Objects.equals(config, prepareForBundlingContext.config())
                && Objects.equals(installedWebDependencies.list(), prepareForBundlingContext.dependencies())
                && !liveReload.getChangedResources().contains(config.fromWebRoot("tsconfig.json"))
                && entryPoints.equals(prepareForBundlingContext.entryPoints())) {
            // We need to set non-restart watched file again
            for (EntryPointBuildItem entryPoint : entryPoints) {
                for (BundleWebAsset webAsset : entryPoint.getWebAssets()) {
                    if (webAsset.isFile() && config.browserLiveReload()) {
                        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                                .setRestartNeeded(webAsset.srcFilePath().isEmpty())
                                .setLocation(webAsset.resourceName())
                                .build());
                    }
                }
            }
            return new ReadyForBundlingBuildItem(prepareForBundlingContext.bundleOptions(), null, targetDir.dist());
        }

        try {
            Files.createDirectories(targetDir.webBundler());
            LOGGER.debugf("Preparing bundle in %s", targetDir);
            final boolean browserLiveReload = launchMode.getLaunchMode().equals(LaunchMode.DEVELOPMENT)
                    && config.browserLiveReload();
            if (bundleConfig.isPresent()) {
                for (WebAsset webAsset : bundleConfig.get().getWebAssets()) {
                    final Path targetConfig = targetDir.webBundler().resolve(webAsset.pathFromWebRoot(config.webRoot()));
                    createAsset(watchedFiles, webAsset, targetConfig, browserLiveReload);
                }
            }

            final Map<String, EsBuildConfig.Loader> loaders = computeLoaders(config);
            final EsBuildConfigBuilder esBuildConfigBuilder = EsBuildConfig.builder()
                    .loader(loaders)
                    .outDir(PathUtils.join(DIST, config.bundlePath()))
                    .publicPath(config.publicBundlePath())
                    .splitting(config.bundling().splitting())
                    .sourceMap(config.bundling().sourceMapEnabled());

            if (browserLiveReload) {
                esBuildConfigBuilder
                        .preserveSymlinks()
                        .minify(false)
                        .define("process.env.LIVE_RELOAD_PATH",
                                "'" + PathUtils.join(httpBuildTimeConfig.rootPath, WEB_BUNDLER_LIVE_RELOAD_PATH)
                                        + "'")
                        .fixedEntryNames();
            }
            if (!config.bundling().envs().isEmpty()) {
                esBuildConfigBuilder.define(config.bundling().safeEnvs());
            }
            if (config.bundling().external().isPresent()) {
                for (String e : config.bundling().external().get()) {
                    esBuildConfigBuilder.addExternal(e);
                }
            } else {
                esBuildConfigBuilder.addExternal(join(config.httpRootPath(), "static/*"));
            }
            final BundleOptionsBuilder optionsBuilder = BundleOptions.builder()
                    .withWorkDir(targetDir.webBundler())
                    .withDependencies(installedWebDependencies.toEsBuildWebDependencies())
                    .withEsConfig(esBuildConfigBuilder.build())
                    .withNodeModulesDir(installedWebDependencies.nodeModulesDir());
            final Set<String> directWebDependenciesIds = installedWebDependencies.list().stream().filter(Dependency::direct)
                    .map(Dependency::id).collect(Collectors.toSet());
            int addedEntryPoints = 0;
            final AutoEntryPoint.AutoDepsMode autoDepsMode = AutoEntryPoint.AutoDepsMode
                    .valueOf(config.dependencies().autoImport().mode().toString());
            for (EntryPointBuildItem entryPoint : entryPoints) {
                final List<String> scripts = new ArrayList<>();
                for (BundleWebAsset webAsset : entryPoint.getWebAssets()) {
                    String destination = webAsset.pathFromWebRoot(config.webRoot());
                    final Path scriptPath = targetDir.webBundler().resolve(destination);
                    if (!isLiveReload
                            || liveReload.getChangedResources().contains(webAsset.resourceName())
                            || !Files.exists(scriptPath)) {
                        createAsset(watchedFiles, webAsset, scriptPath, browserLiveReload);
                    }
                    // Manual assets are supposed to be imported by the entry point
                    if (!webAsset.type().equals(MANUAL)) {
                        scripts.add(destination);
                    }
                }
                String scriptsLog = scripts.stream()
                        .map(s -> String.format("  - %s", s))
                        .collect(
                                Collectors.joining("\n"));

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Bundling '%s' (%d files):\n%s", entryPoint.getEntryPointKey(), scripts.size(), scriptsLog);
                } else {
                    LOGGER.infof("Bundling '%s' (%d files)", entryPoint.getEntryPointKey(), scripts.size());
                }

                if (!scripts.isEmpty()) {
                    if (browserLiveReload) {
                        Files.write(targetDir.webBundler().resolve("live-reload.js"), readLiveReloadJs());
                        scripts.add("live-reload.js");
                    }

                    optionsBuilder.addAutoEntryPoint(targetDir.webBundler(), entryPoint.getEntryPointKey(), scripts,
                            autoDepsMode,
                            directWebDependenciesIds::contains);
                    addedEntryPoints++;
                }
            }

            if (addedEntryPoints == 0) {
                List<String> scripts = new ArrayList<>();
                if (browserLiveReload) {
                    Files.write(targetDir.webBundler().resolve("live-reload.js"), readLiveReloadJs());
                    scripts.add("live-reload.js");
                }
                optionsBuilder.addAutoEntryPoint(targetDir.webBundler(), MAIN_ENTRYPOINT_KEY, scripts, autoDepsMode,
                        directWebDependenciesIds::contains);
                LOGGER.info("No custom entry points found, it will be generated based on web dependencies.");
            }

            final BundleOptions options = optionsBuilder.build();
            liveReload.setContextObject(PrepareForBundlingContext.class,
                    new PrepareForBundlingContext(config, installedWebDependencies.list(), entryPoints, options));
            return new ReadyForBundlingBuildItem(options, started, targetDir.dist());
        } catch (IOException e) {
            liveReload.setContextObject(PrepareForBundlingContext.class, new PrepareForBundlingContext());
            throw new UncheckedIOException(e);
        }
    }

    static void createAsset(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles, WebAsset webAsset, Path targetPath,
            boolean browserLiveReload) throws IOException {
        Files.createDirectories(targetPath.getParent());
        if (!webAsset.isFile()) {
            Files.write(targetPath, webAsset.resource().content(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            if (browserLiveReload) {
                createSymbolicLink(watchedFiles, webAsset, targetPath);
            } else {
                Files.copy(webAsset.resource().path(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void createSymbolicLink(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles, WebAsset webAsset,
            Path targetPath) throws IOException {
        Files.deleteIfExists(targetPath);
        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                .setRestartNeeded(webAsset.srcFilePath().isEmpty())
                .setLocation(webAsset.resourceName())
                .build());
        Files.createSymbolicLink(targetPath, webAsset.srcFilePath().orElse(webAsset.resource().path()));
    }

    private byte[] readLiveReloadJs() throws IOException {
        try (InputStream resourceAsStream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("web-bundler/live-reload.js")) {
            requireNonNull(resourceAsStream, "resource web-bundler/live-reload.js is required");
            return resourceAsStream.readAllBytes();
        }
    }

    private Map<String, EsBuildConfig.Loader> computeLoaders(WebBundlerConfig config) {
        Map<String, EsBuildConfig.Loader> loaders = new HashMap<>();
        for (EsBuildConfig.Loader loader : EsBuildConfig.Loader.values()) {
            final Function<LoadersConfig, Optional<Set<String>>> configFn = requireNonNull(LOADER_CONFIGS.get(loader));
            final Optional<Set<String>> values = configFn.apply(config.bundling().loaders());
            if (values.isPresent()) {
                for (String v : values.get()) {
                    final String ext = v.startsWith(".") ? v : "." + v;
                    if (loaders.containsKey(ext)) {
                        throw new ConfigurationException(
                                "A Web Bundler file extension for loaders is provided more than once: " + ext);
                    }
                    loaders.put(ext, loader);
                }
            }
        }
        return loaders;
    }

    record PrepareForBundlingContext(WebBundlerConfig config, List<Dependency> dependencies,
            List<EntryPointBuildItem> entryPoints,
            BundleOptions bundleOptions) {

        public PrepareForBundlingContext() {
            this(null, null, null, null);
        }
    }

}
