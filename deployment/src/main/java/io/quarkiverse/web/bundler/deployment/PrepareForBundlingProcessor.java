package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundleWebAssetsScannerProcessor.*;
import static io.quarkiverse.web.bundler.deployment.WebBundlerConfig.MAIN_ENTRYPOINT_KEY;
import static io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType.MANUAL;
import static io.quarkiverse.web.bundler.deployment.util.PathUtils.join;
import static io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourcesProcessor.WEB_BUNDLER_LIVE_RELOAD_PATH;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
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
    private static final String LAUNCH_MODE_ENV = "LAUNCH_MODE";

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
        boolean useEsbuildWatch = config.browserLiveReload() && SYMLINK_AVAILABLE.get();
        if (isLiveReload
                && WebBundlerConfig.isEqual(config, prepareForBundlingContext.config())
                && Objects.equals(installedWebDependencies.list(), prepareForBundlingContext.dependencies())
                && bundleConfig.map(b -> b.getWebAssets().stream().map(WebAsset::watchedPath).filter(Optional::isPresent)
                        .map(Optional::get)
                        .noneMatch(liveReload.getChangedResources()::contains)).orElse(true)
                && entryPoints.equals(prepareForBundlingContext.entryPoints())
                && entryPoints.stream().map(EntryPointBuildItem::assets).flatMap(List::stream)
                        .map(WebAsset::watchedPath)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .noneMatch(liveReload.getChangedResources()::contains)) {
            // We need to set non-restart watched file again
            for (EntryPointBuildItem entryPoint : entryPoints) {
                for (BundleWebAsset webAsset : entryPoint.assets()) {
                    if (webAsset.watchedPath().isPresent() && webAsset.path().isPresent()) {
                        boolean restartNeeded = !useEsbuildWatch || !webAsset.isSource();
                        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                                .setRestartNeeded(restartNeeded)
                                .setLocation(webAsset.watchedPath().get())
                                .build());
                    }

                }
            }
            return new ReadyForBundlingBuildItem(prepareForBundlingContext.bundleOptions(), null, targetDir.dist(),
                    useEsbuildWatch);
        }

        try {
            Files.createDirectories(targetDir.webBundler());
            LOGGER.debugf("Preparing bundle in %s", targetDir);
            final boolean browserLiveReload = launchMode.getLaunchMode().equals(LaunchMode.DEVELOPMENT)
                    && config.browserLiveReload();
            if (bundleConfig.isPresent()) {
                for (WebAsset webAsset : bundleConfig.get().getWebAssets()) {
                    final Path targetConfig = targetDir.webBundler().resolve(webAsset.relativePath());
                    createAsset(config, watchedFiles, webAsset, targetConfig, browserLiveReload);
                }
            }

            final Map<String, EsBuildConfig.Loader> loaders = computeLoaders(config);
            final EsBuildConfigBuilder esBuildConfigBuilder = EsBuildConfig.builder()
                    .loader(loaders)
                    .outDir(PathUtils.join(DIST, config.bundlePath()))
                    .publicPath(config.publicBundlePath())
                    .splitting(config.bundling().splitting())
                    .sourceMap(config.bundling().sourceMapEnabled())
                    .define(LAUNCH_MODE_ENV, "'" + launchMode.getLaunchMode().name() + "'");
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
                for (BundleWebAsset webAsset : entryPoint.assets()) {
                    String destination = webAsset.relativePath();
                    final Path scriptPath = targetDir.webBundler().resolve(destination);
                    if (!isLiveReload
                            || webAsset.watchedPath().isEmpty()
                            || liveReload.getChangedResources().contains(webAsset.watchedPath().get())
                            || !Files.exists(scriptPath)) {
                        createAsset(config, watchedFiles, webAsset, scriptPath, browserLiveReload);
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
                    LOGGER.debugf("Bundling '%s' (%d files):\n%s", entryPoint.key(), scripts.size(), scriptsLog);
                } else {
                    LOGGER.infof("Bundling '%s' (%d files)", entryPoint.key(), scripts.size());
                }

                if (!scripts.isEmpty()) {
                    if (browserLiveReload) {
                        Files.write(targetDir.webBundler().resolve("live-reload.js"), readLiveReloadJs());
                        scripts.add("live-reload.js");
                    }

                    optionsBuilder.addAutoEntryPoint(targetDir.webBundler(), entryPoint.key(), scripts,
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
            return new ReadyForBundlingBuildItem(options, started, targetDir.dist(), useEsbuildWatch);
        } catch (IOException e) {
            liveReload.setContextObject(PrepareForBundlingContext.class, new PrepareForBundlingContext());
            throw new UncheckedIOException(e);
        }
    }

    static void createAsset(WebBundlerConfig config, BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            WebAsset webAsset, Path targetPath,
            boolean browserLiveReload) throws IOException {
        Files.createDirectories(targetPath.getParent());
        if (webAsset.path().isEmpty()) {
            Files.write(targetPath, webAsset.content(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            if (browserLiveReload && SYMLINK_AVAILABLE.get()) {
                createSymbolicLinkOrFallback(watchedFiles, webAsset, targetPath);
            } else {
                Files.copy(webAsset.path().get(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void createSymbolicLinkOrFallback(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles, WebAsset webAsset,
            Path targetPath) throws IOException {
        Files.deleteIfExists(targetPath);
        if (webAsset.path().isEmpty() || webAsset.watchedPath().isEmpty()) {
            return;
        }
        boolean restartNeeded = true;
        if (webAsset.isSource()) {
            try {
                Files.createSymbolicLink(targetPath, webAsset.path().get());
                restartNeeded = false;
            } catch (FileSystemException e) {
                if (SYMLINK_AVAILABLE.getAndSet(false)) {
                    LOGGER.warn(
                            "Creating a symbolic link was not authorized on this system. It is required by the Web Bundler to allow filesystem watch. As a result, Web Bundler live-reload will use a scheduler as a fallback.\n\nTo resolve this issue, please add the necessary permissions to allow symbolic link creation.");
                }
            }
        } else {
            if (SYMLINK_AVAILABLE.getAndSet(false)) {
                LOGGER.warn(
                        "The sources are necessary by the Web Bundler to allow filesystem watch. Web Bundler live-reload will use a scheduler as a fallback");
            }
            Files.copy(webAsset.path().get(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                .setRestartNeeded(restartNeeded)
                .setLocation(webAsset.watchedPath().get())
                .build());
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
