package crelia.launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

public final class Main {
    private static final String INDEX_RESOURCE = "/META-INF/crelia-libraries.index";
    private static final String LIBRARY_RESOURCE_PREFIX = "/META-INF/crelia-libraries/";
    private static final List<String> JVM_ARGS = List.of(
        "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens=java.base/java.util.jar=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Dnet.kyori.adventure.text.warnWhenLegacyFormattingDetected=true",
        "-Dio.papermc.paper.suppress.sout.nags=true",
        "-Dpaper.maxChatCommandInputSize=32767"
    );
    private static final List<String> SERVER_ARGS = List.of(
        "--nogui",
        "--fml.mcVersion", "1.21.1",
        "--fml.neoForgeVersion", "21.1.79",
        "--fml.neoFormVersion", "1"
    );

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (!hasAgreedToEula(Path.of("eula.txt"))) {
            return;
        }

        Path outputDir = Path.of("crelia-libraries").toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        List<Entry> entries = readEntries();
        List<String> classpath = new ArrayList<>(entries.size());

        System.out.println("Checking embedded Crelia libraries in " + outputDir);
        for (Entry entry : entries) {
            Path outputFile = outputDir.resolve(entry.name()).normalize();
            if (!outputFile.startsWith(outputDir)) {
                throw new IllegalStateException("Invalid embedded library path: " + entry.name());
            }
            if (!Files.isRegularFile(outputFile) || !entry.sha256().equalsIgnoreCase(sha256(outputFile))) {
                extract(entry, outputFile);
            }
            classpath.add(outputFile.toString());
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(JVM_ARGS);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpath));
        command.add("crelia.CreliaServer");
        command.addAll(SERVER_ARGS);
        command.addAll(Arrays.asList(args));

        System.out.println("Starting Crelia server");
        int exitCode = new ProcessBuilder(command)
            .inheritIO()
            .start()
            .waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static boolean hasAgreedToEula(Path eulaFile) throws IOException {
        Properties properties = new Properties();
        if (Files.isRegularFile(eulaFile)) {
            try (InputStream input = Files.newInputStream(eulaFile)) {
                properties.load(input);
            }
            if (Boolean.parseBoolean(properties.getProperty("eula", "false"))) {
                return true;
            }
        } else {
            properties.setProperty("eula", "false");
            try (OutputStream output = Files.newOutputStream(eulaFile)) {
                properties.store(output, "By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA).");
            }
        }

        System.out.println("You need to agree to the Minecraft EULA before the server can start.");
        System.out.println("Edit eula.txt, set eula=true, then run this jar again.");
        return false;
    }

    private static List<Entry> readEntries() throws IOException {
        try (InputStream input = Main.class.getResourceAsStream(INDEX_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing embedded library index: " + INDEX_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                    .filter(line -> !line.isBlank())
                    .map(Entry::parse)
                    .toList();
            }
        }
    }

    private static void extract(Entry entry, Path outputFile) throws IOException {
        System.out.println("Unpacking " + entry.name());
        Files.createDirectories(outputFile.getParent());
        Path temporaryFile = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
        try (InputStream input = Main.class.getResourceAsStream(LIBRARY_RESOURCE_PREFIX + entry.name())) {
            if (input == null) {
                throw new IllegalStateException("Missing embedded library: " + entry.name());
            }
            Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!entry.sha256().equalsIgnoreCase(sha256(temporaryFile))) {
            throw new IllegalStateException("Embedded library checksum mismatch: " + entry.name());
        }
        Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(file)) {
            input.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", executableName);
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Cannot find Java executable: " + executable);
        }
        return executable;
    }

    private record Entry(String sha256, String name) {
        private static Entry parse(String line) {
            String[] fields = line.split("\t", 2);
            if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IllegalStateException("Malformed embedded library index line: " + line);
            }
            return new Entry(fields[0], fields[1]);
        }
    }
}
