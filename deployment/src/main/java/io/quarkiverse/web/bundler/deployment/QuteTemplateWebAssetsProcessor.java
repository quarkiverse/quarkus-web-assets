package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.util.PathUtils.prefixWithSlash;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkiverse.web.bundler.deployment.items.GeneratedBundleBuildItem;
import io.quarkiverse.web.bundler.deployment.items.QuteTemplatesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem.SourceType;
import io.quarkiverse.web.bundler.runtime.Bundle;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.qute.*;

public class QuteTemplateWebAssetsProcessor {
    @BuildStep
    void processHtmlTemplateWebAssets(QuteTemplatesBuildItem htmlTemplates,
            GeneratedBundleBuildItem generatedBundle,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer,
            LaunchModeBuildItem launchMode) {
        if (htmlTemplates.getWebAssets().isEmpty()) {
            return;
        }
        final Map<String, String> bundle = generatedBundle != null ? generatedBundle.getBundle() : Map.of();
        final Bundle.Mapping mapping = new Bundle.Mapping() {
            @Override
            public String get(String name) {
                return bundle.get(name);
            }

            @Override
            public Set<String> names() {
                return bundle.keySet();
            }
        };
        final Engine engine = Engine.builder()
                .addDefaults()
                .addNamespaceResolver(NamespaceResolver.builder("inject")
                        .resolve((c) -> c.getName().equals("bundle") ? new Bundle(mapping) : null)
                        .build())
                .addNamespaceResolver(NamespaceResolver.builder("build")
                        .resolve((c) -> c.getName().equals("launchMode") ? launchMode.getLaunchMode().toString() : null)
                        .build())
                .addNamespaceResolver(NamespaceResolver.builder("config")
                        .resolveAsync(this::resolveConfig)
                        .build())
                .addLocator(new WebBundlerTagsLocator())
                .addSectionHelper(new UserTagSectionHelper.Factory("bundle", "web-bundler/bundle.html"))
                .addValueResolver(new ReflectionValueResolver())
                .addParserHook(new Qute.IndexedArgumentsParserHook())
                .addResultMapper(new HtmlEscaper(ImmutableList.of("text/html", "text/xml")))
                .build();
        for (WebAsset webAsset : htmlTemplates.getWebAssets()) {
            final byte[] bytes = webAsset.content();
            final String content = engine.parse(new String(bytes, webAsset.charset())).render();
            staticResourceProducer.produce(GeneratedWebResourceBuildItem.fromContent(prefixWithSlash(webAsset.relativePath()),
                    content.getBytes(), SourceType.BUILD_TIME_TEMPLATE));
        }
    }

    private CompletionStage<Object> resolveConfig(EvalContext ctx) {
        List<Expression> params = ctx.getParams();
        final String name = ctx.getName();
        if (params.isEmpty()) {
            return findConfig(name, String.class);
        }
        if (name.equals("boolean")) {
            return ctx.evaluate(params.get(0)).thenCompose(propertyName -> findConfig(propertyName.toString(), Boolean.class));
        }
        if (name.equals("integer")) {
            return ctx.evaluate(params.get(0)).thenCompose(propertyName -> findConfig(propertyName.toString(), Integer.class));
        }
        return ctx.evaluate(params.get(0)).thenCompose(propertyName -> findConfig(propertyName.toString(), String.class));
    }

    private static <T> CompletableFuture<Object> findConfig(String propertyName, Class<T> type) {
        Optional<T> val = ConfigProvider.getConfig().getOptionalValue(propertyName, type);
        return completedFuture(val.isPresent() ? val.get() : Results.NotFound.from(propertyName));
    }

    private static final class WebBundlerTagsLocator implements TemplateLocator {
        @Override
        public Optional<TemplateLocation> locate(String id) {
            if (!id.startsWith("web-bundler/")) {
                return Optional.empty();
            }
            String name = id.replace("web-bundler/", "");
            final URL resource = this.getClass().getResource("/templates/tags/" + name);
            if (resource == null) {
                return Optional.empty();
            }
            return Optional.of(new TemplateLocation() {
                @Override
                public Reader read() {
                    try {
                        return new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                @Override
                public Optional<Variant> getVariant() {
                    return Optional.empty();
                }
            });
        }

    }
}
