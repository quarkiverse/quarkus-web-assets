package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.util.PathUtils.*;
import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import io.mvnpm.esbuild.Bundler;
import io.mvnpm.esbuild.model.BundleResult;
import io.quarkiverse.web.bundler.deployment.items.GeneratedBundleBuildItem;
import io.quarkiverse.web.bundler.deployment.items.GeneratedEntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ReadyForBundlingBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem.SourceType;
import io.quarkiverse.web.bundler.runtime.Bundle;
import io.quarkiverse.web.bundler.runtime.BundleRedirectHandlerRecorder;
import io.quarkiverse.web.bundler.runtime.WebBundlerBuildRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.deployment.RouteBuildItem;

class BundlingProcessor {

    private static final Logger LOGGER = Logger.getLogger(BundlingProcessor.class);

    @BuildStep(onlyIfNot = IsDevelopment.class)
    void bundle(WebBundlerConfig config,
            ReadyForBundlingBuildItem readyForBundling,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer,
            BuildProducer<GeneratedBundleBuildItem> generatedBundleProducer,
            BuildProducer<GeneratedEntryPointBuildItem> generatedEntryPointProducer) {
        if (readyForBundling == null) {
            return;
        }
        bundleAndProcess(config, readyForBundling, staticResourceProducer, generatedBundleProducer,
                generatedEntryPointProducer);
    }

    static BundleResult bundleAndProcess(WebBundlerConfig config, ReadyForBundlingBuildItem readyForBundling,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer,
            BuildProducer<GeneratedBundleBuildItem> generatedBundleProducer,
            BuildProducer<GeneratedEntryPointBuildItem> generatedEntryPointProducer) {
        try {
            final long startedBundling = Instant.now().toEpochMilli();
            final BundleResult result = Bundler.bundle(readyForBundling.bundleOptions(), false);
            if (!result.result().output().isBlank()) {
                LOGGER.debugf(result.result().output());
            }

            handleBundleDistDir(config, generatedBundleProducer, staticResourceProducer, result.dist(), startedBundling);
            processGeneratedEntryPoints(readyForBundling.bundleOptions().workDir(), generatedEntryPointProducer);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void processGeneratedEntryPoints(Path targetDir,
            BuildProducer<GeneratedEntryPointBuildItem> generatedEntryPointProducer) {
        try (Stream<Path> generatedEPStream = Files.find(targetDir, 1, (path, basicFileAttributes) -> Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase().endsWith(".js"))) {
            generatedEPStream
                    .forEach(p -> {
                        final String key = p.getFileName().toString().replace(".js", "");
                        generatedEntryPointProducer
                                .produce(new GeneratedEntryPointBuildItem(key, p.getFileName().toString()));
                    });

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    static void handleBundleDistDir(WebBundlerConfig config, BuildProducer<GeneratedBundleBuildItem> generatedBundleProducer,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer, Path bundleDir, Long started) {
        try {
            Map<String, String> bundle = new HashMap<>();
            List<String> names = new ArrayList<>();
            StringBuilder mappingString = new StringBuilder();
            try (Stream<Path> stream = Files.find(bundleDir, 20, (p, i) -> Files.isRegularFile(p))) {
                stream.forEach(path -> {
                    final String relativePath = toUnixPath(bundleDir.relativize(path).toString());
                    final String key = relativePath.replaceAll("-[^-.]+\\.", ".");
                    final String publicBundleAssetPath = join(config.publicBundlePath(), relativePath);
                    final String fileName = path.getFileName().toString();
                    final String ext = fileName.substring(fileName.indexOf("."));
                    if (Bundle.BUNDLE_MAPPING_EXT.contains(ext)) {
                        mappingString.append("  ").append(key).append(" => ").append(publicBundleAssetPath).append("\n");
                        bundle.put(key, publicBundleAssetPath);
                    }
                    names.add(publicBundleAssetPath);
                    if (config.shouldQuarkusServeBundle()) {
                        // The root-path will already be added by the static resources handler
                        final String resourcePath = surroundWithSlashes(config.bundlePath()) + relativePath;
                        staticResourceProducer.produce(GeneratedWebResourceBuildItem.fromFile(resourcePath, path.normalize(),
                                SourceType.BUNDLED_ASSET));
                    }
                });
            }
            if (started != null) {
                LOGGER.infof("Bundle generated %d files in %sms", names.size(),
                        Instant.now().minusMillis(started).toEpochMilli());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debugf("Bundle dir: '%s'\n  - %s", bundleDir, names.size(),
                            String.join("\n  - ", names));
                }
                if (LOGGER.isDebugEnabled() || LaunchMode.current() == LaunchMode.DEVELOPMENT) {
                    LOGGER.infof("Bundle#mapping:\n%s", mappingString);
                }

            }
            generatedBundleProducer.produce(new GeneratedBundleBuildItem(bundleDir, bundle));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @BuildStep
    @Record(STATIC_INIT)
    void initBundleBean(
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            GeneratedBundleBuildItem generatedBundle,
            WebBundlerBuildRecorder recorder) {
        final Map<String, String> bundle = generatedBundle != null ? generatedBundle.getBundle() : Map.of();
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(Bundle.Mapping.class)
                .supplier(recorder.createContext(bundle))
                .done());
        additionalBeans.produce(new AdditionalBeanBuildItem(Bundle.class));
    }

    @BuildStep
    @Record(STATIC_INIT)
    void initBundleRedirect(WebBundlerConfig config, BuildProducer<RouteBuildItem> routes,
            BundleRedirectHandlerRecorder recorder, GeneratedBundleBuildItem generatedBundle) {
        if (config.bundleRedirect() && generatedBundle != null) {
            routes.produce(RouteBuildItem.builder().route(join(prefixWithSlash(config.bundlePath()), "*"))
                    .handler(recorder.handler(generatedBundle.getBundle()))
                    .build());
        }
    }

}
