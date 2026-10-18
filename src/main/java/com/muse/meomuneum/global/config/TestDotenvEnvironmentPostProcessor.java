package com.muse.meomuneum.global.config;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

public final class TestDotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "testDotenv";
    private static final Pattern DECLARATION = Pattern.compile("\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.*)");

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("test & !prod")) ||
                environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        Path root;
        Path legacy;
        try {
            root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            legacy = root.resolve(environment.getProperty("LOCAL_ENV_PROPERTIES", ".env.local.properties")).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Cannot load test dotenv working directory");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Path file : discover(root, legacy)) {
            load(file, values);
        }
        if (!values.isEmpty()) {
            addPropertySource(environment.getPropertySources(), values);
        }
    }

    private List<Path> discover(Path root, Path legacy) {
        List<Path> files = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if ((name.equals(".env") || name.startsWith(".env.")) &&
                        !name.endsWith(".example") && !name.endsWith(".sample") && !name.endsWith(".template") &&
                        !name.equals(".env.local.properties") && !path.equals(legacy) &&
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(path);
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException exception) {
            throw new IllegalStateException("Cannot load test dotenv working directory");
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private void load(Path file, Map<String, Object> values) {
        try {
            for (String line : Files.readAllLines(file)) {
                validateLine(line);
            }
            Dotenv dotenv = Dotenv.configure().directory(file.getParent().resolve(".").toString())
                    .filename(file.getFileName().toString()).load();
            for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                String value = entry.getValue();
                if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(entry.getKey(), value);
            }
        } catch (IOException | RuntimeException exception) {
            String filename = file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
            throw new IllegalStateException("Cannot load test dotenv file " + filename);
        }
    }

    private void validateLine(String line) {
        if (line.isBlank() || line.startsWith("#")) {
            return;
        }
        Matcher declaration = DECLARATION.matcher(line);
        if (!declaration.matches()) {
            throw new IllegalArgumentException();
        }
        String value = declaration.group(2).stripTrailing();
        if (value.isEmpty() || value.startsWith("#")) {
            return;
        }
        char quote = value.charAt(0);
        if (quote != '\'' && quote != '"') {
            String unquoted = value.split("#", 2)[0];
            if (unquoted.indexOf('\'') >= 0 || unquoted.indexOf('"') >= 0) {
                throw new IllegalArgumentException();
            }
            return;
        }
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) == quote &&
                    (quote != '"' || value.charAt(index - 1) != '\\')) {
                String trailing = value.substring(index + 1).stripLeading();
                if (trailing.isEmpty() || trailing.startsWith("#")) {
                    return;
                }
                break;
            }
        }
        throw new IllegalArgumentException();
    }

    private void addPropertySource(MutablePropertySources sources, Map<String, Object> values) {
        var dotenv = new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, values);
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, dotenv);
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, dotenv);
        } else {
            for (var source : sources) {
                if (source.getName().startsWith("Config resource ") || source.getName().equals("defaultProperties")) {
                    sources.addBefore(source.getName(), dotenv);
                    return;
                }
            }
            sources.addLast(dotenv);
        }
    }
}
