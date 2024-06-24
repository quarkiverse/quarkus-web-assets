package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.util.PathUtils.addTrailingSlash;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig.EntryPointConfig;
import io.quarkiverse.web.bundler.deployment.items.BundleConfigAssetsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.BundleWebAsset;
import io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType;
import io.quarkiverse.web.bundler.deployment.items.EntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem.Scanner;
import io.quarkiverse.web.bundler.deployment.items.QuteTagsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.bootstrap.workspace.SourceDir;
import io.quarkus.bootstrap.workspace.WorkspaceModule;
import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.*;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.maven.dependency.ResolvedDependency;

class BundleWebAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(BundleWebAssetsScannerProcessor.class);

    private static final String FEATURE = "web-bundler";
    public static final String MAIN_ENTRYPOINT_KEY = "main";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    ProjectResourcesScannerBuildItem initScanner(LaunchModeBuildItem launchMode,
            ApplicationArchivesBuildItem applicationArchives,
            CurateOutcomeBuildItem curateOutcome) {
        Set<ApplicationArchive> allApplicationArchives = applicationArchives.getAllApplicationArchives();
        List<ResolvedDependency> extensionArtifacts = curateOutcome.getApplicationModel().getDependencies().stream()
                .filter(Dependency::isRuntimeExtensionArtifact).collect(Collectors.toList());

        final List<Path> srcResourcesDirs = launchMode.getLaunchMode().isDevOrTest() ? findSrcResourcesDirs(curateOutcome)
                : List.of();
        return new ProjectResourcesScannerBuildItem(allApplicationArchives, extensionArtifacts, srcResourcesDirs);
    }

    private static List<Path> findSrcResourcesDirs(CurateOutcomeBuildItem curateOutcome) {
        final List<Path> paths = new ArrayList<>();
        for (WorkspaceModule workspaceModule : curateOutcome.getApplicationModel().getWorkspaceModules()) {
            for (SourceDir resourceDir : workspaceModule.getMainSources().getResourceDirs()) {
                paths.add(resourceDir.getDir());
            }
        }
        return paths;
    }

    @BuildStep
    void collect(ProjectResourcesScannerBuildItem scanner,
            BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<QuteTagsBuildItem> quteTagsAssets,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {
        watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                .setLocationPredicate(s -> s.startsWith(config.webRoot())).setRestartNeeded(true).build());
        Map<String, EntryPointConfig> entryPointsConfig = new HashMap<>(config.bundle());
        if (!config.bundle().containsKey("app")) {
            entryPointsConfig.put("app", new ConfiguredEntryPoint("app", "app", MAIN_ENTRYPOINT_KEY));
        }

        final WebAssetsLookupDevContext devContext = liveReload.getContextObject(WebAssetsLookupDevContext.class);
        if (liveReload.isLiveReload()
                && devContext != null
                && Objects.equals(config, devContext.config())
                && !hasChanged(config, liveReload, c -> isBundleFile(config, c, entryPointsConfig))) {
            // Project WebAssets shouldn't be changed even if the file is changed as content is not stored
            LOGGER.debug("Web bundler scan not needed for live reload");
            produceWebAssets(bundles, quteTagsAssets, bundleConfigAssets, devContext, true);
            return;
        }
        LOGGER.debug("Web bundler scan started");
        final List<Scanner> quteTagsAssetsScanners = new ArrayList<>();
        final List<Scanner> bundleConfigAssetsScanners = new ArrayList<>();

        final Map<String, List<BundleWebAsset>> bundleAssets = new HashMap<>();
        for (Map.Entry<String, EntryPointConfig> e : entryPointsConfig.entrySet()) {
            if (e.getValue().enabled()) {
                final String entryPointKey = e.getValue().effectiveKey(e.getKey());
                bundleAssets.putIfAbsent(entryPointKey, new ArrayList<>());
                final String dirFromWebRoot = config.fromWebRoot(e.getValue().effectiveDir(e.getKey()));

                if (e.getValue().quteTags()) {
                    quteTagsAssetsScanners.add(new Scanner(dirFromWebRoot,
                            "glob:**.html", config.charset()));
                }
                // The regex is for all files but .html
                final List<WebAsset> assets = scanner.scan(dirFromWebRoot, "regex:^(.(?!\\.html$))*$",
                        config.charset());
                final Optional<WebAsset> entryPoint = assets.stream()
                        .filter(w -> w.resourceName().startsWith(addTrailingSlash(dirFromWebRoot) + "index."))
                        .findAny();
                for (WebAsset webAsset : assets) {
                    BundleType bundleType = entryPoint
                            // If it's not the entry point we consider it as a manual asset (imported by the entry point)
                            .map(ep -> webAsset.equals(ep) ? BundleType.INDEX : BundleType.MANUAL)
                            // When there is no entry point we consider it as a auto asset unless it's a sass import file (_*.sass)
                            .orElse(isImportSassFile(webAsset.resourceName()) ? BundleType.MANUAL : BundleType.AUTO);
                    bundleAssets.get(entryPointKey).add(new BundleWebAsset(webAsset, bundleType));
                }
            }
        }

        bundleConfigAssetsScanners.add(new Scanner(config.webRoot(), "glob:tsconfig.json", config.charset()));

        final WebAssetsLookupDevContext context = new WebAssetsLookupDevContext(
                config,
                bundleAssets,
                scanner.scan(quteTagsAssetsScanners),
                scanner.scan(bundleConfigAssetsScanners));
        produceWebAssets(bundles, quteTagsAssets, bundleConfigAssets, context, false);
        liveReload.setContextObject(WebAssetsLookupDevContext.class, context);
    }

    private static boolean isImportSassFile(String resourceName) {
        final String fileName = Path.of(resourceName).getFileName().toString();
        return fileName.startsWith("_") && isSassFile(fileName);
    }

    private static boolean isSassFile(String fileName) {
        String lc = fileName.toLowerCase();
        return lc.endsWith(".scss") || lc.endsWith(".sass");
    }

    private static boolean isBundleFile(WebBundlerConfig config, String changedResource,
            Map<String, EntryPointConfig> entryPointsConfig) {
        return config.fromWebRoot("tsconfig.json").equals(changedResource)
                || entryPointsConfig.entrySet().stream().map(e -> config.fromWebRoot(e.getValue().effectiveDir(e.getKey())))
                        .anyMatch(changedResource::startsWith);
    }

    static boolean hasChanged(WebBundlerConfig config,
            LiveReloadBuildItem liveReload,
            Predicate<String> predicate) {
        // Let's just check if the file changed is in the webRoot
        return liveReload.getChangedResources().stream().anyMatch(c -> c.startsWith(config.webRoot()) && predicate.test(c));
    }

    void produceWebAssets(BuildProducer<EntryPointBuildItem> bundles,
            BuildProducer<QuteTagsBuildItem> quteTagsAssets,
            BuildProducer<BundleConfigAssetsBuildItem> bundleConfigAssets,
            WebAssetsLookupDevContext context,
            boolean checkIfExists) {
        for (Map.Entry<String, List<BundleWebAsset>> e : context.bundleAssets().entrySet()) {
            produceWebAssetsWithCheck(checkIfExists, e.getValue(),
                    webAssets -> {
                        bundles.produce(new EntryPointBuildItem(e.getKey(), webAssets));
                    });
        }

        produceWebAssetsWithCheck(checkIfExists, context.bundleConfigWebAssets(),
                webAssets -> bundleConfigAssets.produce(new BundleConfigAssetsBuildItem(webAssets)));

        produceWebAssetsWithCheck(checkIfExists, context.quteWebAssets(),
                webAssets -> quteTagsAssets.produce(new QuteTagsBuildItem(webAssets)));
    }

    private static <T extends WebAsset> void produceWebAssetsWithCheck(boolean checkIfExists, List<T> e,
            Consumer<List<T>> consumer) {
        final List<T> webAssets = checkIfExists ? checkWebAssets(e) : e;
        if (!webAssets.isEmpty()) {
            consumer.accept(webAssets);
        }
    }

    private static <T extends WebAsset> List<T> checkWebAssets(List<T> webAssets) {
        return webAssets.stream().filter(w -> w.isFile() && Files.isRegularFile(w.resource().path()))
                .collect(
                        Collectors.toList());
    }

    record WebAssetsLookupDevContext(WebBundlerConfig config, Map<String, List<BundleWebAsset>> bundleAssets,
            List<WebAsset> quteWebAssets, List<WebAsset> bundleConfigWebAssets) {

        public List<WebAsset> allWebAssets() {
            final ArrayList<WebAsset> all = new ArrayList<>();
            all.addAll(quteWebAssets);
            all.addAll(bundleConfigWebAssets);
            bundleAssets.values().forEach(all::addAll);
            return all;
        }
    }

}
