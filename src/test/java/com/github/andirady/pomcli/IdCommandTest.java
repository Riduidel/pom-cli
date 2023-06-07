package com.github.andirady.pomcli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import com.google.common.jimfs.Jimfs;

class IdCommandTest {


    IdCommand cmd;
    FileSystem fs;

    @BeforeEach
    void setup() {
        fs = Jimfs.newFileSystem();
        cmd = new IdCommand();
    }

    @Test
    void shouldFailIfNoIdAndNoExistingPom() {
        var pomPath = fs.getPath("pom.xml");

        cmd.pomPath = pomPath;
        assertThrows(Exception.class, cmd::run);
    }

    @Test
    void shouldCreateNewFileIfPomNotExists() {
        var pomPath = fs.getPath("pom.xml");
        var projectId = "com.example:my-app:0.0.1";

        cmd.pomPath = pomPath;
        cmd.id = projectId;
        cmd.run();
        assertTrue(Files.exists(cmd.pomPath));
    }

    @Test
    void shouldSetModelVersionIfCreatingNewPom() throws IOException {
        var pomPath = fs.getPath("pom.xml");
        var projectId = "com.example:my-app:0.0.1";

        cmd.pomPath = pomPath;
        cmd.id = projectId;
        cmd.run();

        var s = Files.readString(pomPath);
        assertTrue(s.contains("<modelVersion>4.0.0</modelVersion>"));
    }

    private static Stream<Arguments> javaVersions() {
        return Stream.of(
            Arguments.of(
                "java 8",
                """
                openjdk version "1.8.0_372"
                OpenJDK Runtime Environment (Temurin)(build 1.8.0_372-b07)
                OpenJDK 64-Bit Server VM (Temurin)(build 25.372-b07, mixed mode)
                """,
                List.of("<maven.compiler.source>1.8</maven.compiler.source>",
                        "<maven.compiler.target>1.8</maven.compiler.target>")

            ),
            Arguments.of(
                "java 11",
                """
                openjdk version "11.0.12" 2021-07-20
                OpenJDK Runtime Environment 18.9 (build 11.0.12+7)
                OpenJDK 64-Bit Server VM 18.9 (build 11.0.12+7, mixed mode)
                """,
                List.of("<maven.compiler.release>11</maven.compiler.release>")
            ),
            Arguments.of(
                "java 21-ea",
                """
                openjdk version "21-ea" 2023-09-19
                OpenJDK Runtime Environment (build 21-ea+25-2212)
                OpenJDK 64-Bit Server VM (build 21-ea+25-2212, mixed mode, sharing)
                """,
                List.of("<maven.compiler.release>21</maven.compiler.release>")
            )
        );
    }

    @ParameterizedTest
    @MethodSource("javaVersions")
    void shouldSetJavaVersionIfCreatingNewPom(String name, String javaVersionOut, List<String> expectedContains)
            throws IOException {
        var pomPath = fs.getPath("pom.xml");
        var projectId = "com.example:my-app:0.0.1";

        var mockProcess = Mockito.mock(Process.class);
        when(mockProcess.getInputStream()).thenReturn(new ByteArrayInputStream(javaVersionOut.getBytes()));
        try (
            var mocked = Mockito.mockConstruction(ProcessBuilder.class, (mock, ctx) -> {
                when(mock.redirectErrorStream(anyBoolean())).thenReturn(mock);
                when(mock.start()).thenReturn(mockProcess);
            })
        ) {
            cmd.pomPath = pomPath;
            cmd.id = projectId;
            cmd.run();

            var s = Files.readString(pomPath);
            assertTrue(expectedContains.stream().allMatch(s::contains), name);
        }
    }

    private static Stream<Arguments> idInputs() {
        return Stream.of(
            Arguments.of("foo", null, "jar unnamed:foo:0.0.1-SNAPSHOT"),
            Arguments.of(".", null, "jar unnamed:my-app:0.0.1-SNAPSHOT"),
            Arguments.of("com.example:my-app", null, "jar com.example:my-app:0.0.1-SNAPSHOT"),
            Arguments.of("com.example:my-app", "war", "war com.example:my-app:0.0.1-SNAPSHOT"),
            Arguments.of("com.example:my-app:1.0.0", null, "jar com.example:my-app:1.0.0"),
            Arguments.of("com.example:my-app:1.0.0", "war", "war com.example:my-app:1.0.0")
        );
    }

    @ParameterizedTest
    @MethodSource("idInputs")
    void shouldMatchExpected(String id, String packaging, String expectedOutput) throws Exception {
        var projectPath = fs.getPath("my-app");
        var pomPath = projectPath.resolve("pom.xml");

        Files.createDirectory(projectPath);

        cmd.pomPath = pomPath;
        cmd.id = id;
        cmd.as = packaging;
		try {
			cmd.run();
		} catch (Exception e) {
            e.printStackTrace();
        }
        assertEquals(expectedOutput, cmd.readProjectId());
    }

    @Test
    void shouldUpdateExistingPom() throws Exception {
        var pomPath = fs.getPath("pom.xml");
        var projectId = "my-app";

        cmd.pomPath = pomPath;
        cmd.id = projectId;
        cmd.run();

        cmd.id = "com.example:my-app";
        cmd.as = "pom";
        cmd.run();
        assertEquals("pom com.example:my-app:0.0.1-SNAPSHOT", cmd.readProjectId());
    }

    @Test
    void shouldAddParentIfAPomProjectIsFoundInTheParentDirectory() throws Exception {
        var aPath = fs.getPath("a");
        var bPath = aPath.resolve("b");
        var parentPomPath = aPath.resolve("pom.xml");
        var pomPath = bPath.resolve("pom.xml");

        Files.createDirectory(aPath);
        Files.createDirectory(bPath);

        var cmd = new IdCommand();
        cmd.pomPath = parentPomPath;
        cmd.id = "a:a:1";
        cmd.as = "pom";
        cmd.run();

        cmd = new IdCommand();
        cmd.pomPath = pomPath;
        cmd.id = "b";
        cmd.run();

        var pat = Pattern.compile("""
                .*<parent>\\s*\
                <groupId>a</groupId>\\s*\
                <artifactId>a</artifactId>\\s*\
                <version>1</version>\\s*\
                </parent>""", Pattern.MULTILINE);
        var s = Files.readString(pomPath);
        var matcher = pat.matcher(s);
        assertNotNull(matcher);
        assertTrue(matcher.find());
        assertEquals("jar a:b:1", cmd.readProjectId());
    }

    @Test
    void shouldAddParentIfAPomProjectIsFoundAboveTheDirectoryTree() throws Exception {
        var aPath = fs.getPath("a");
        var bPath = aPath.resolve("b");
        var cPath = bPath.resolve("c");
        var parentPomPath = aPath.resolve("pom.xml");
        var pomPath = cPath.resolve("pom.xml");

        Files.createDirectory(aPath);
        Files.createDirectory(bPath);
        Files.createDirectory(cPath);

        var cmd = new IdCommand();
        cmd.pomPath = parentPomPath;
        cmd.id = "a:a:1";
        cmd.as = "pom";
        cmd.run();

        cmd = new IdCommand();
        cmd.pomPath = pomPath;
        cmd.id = "c";
        cmd.run();

        var pat = Pattern.compile("""
                .*<parent>\\s*\
                <groupId>a</groupId>\\s*\
                <artifactId>a</artifactId>\\s*\
                <version>1</version>\\s*\
                <relativePath>../..</relativePath>\\s*\
                </parent>""", Pattern.MULTILINE);
        var s = Files.readString(pomPath);
        var matcher = pat.matcher(s);
        assertNotNull(matcher);
        assertTrue(matcher.find());
        assertEquals("jar a:c:1", cmd.readProjectId());
    }

    @Test
    void shouldNotAddParentIfStandalone() throws Exception {
        var aPath = fs.getPath("a");
        var bPath = aPath.resolve("b");
        var parentPomPath = aPath.resolve("pom.xml");
        var pomPath = bPath.resolve("pom.xml");

        Files.createDirectory(aPath);
        Files.createDirectory(bPath);

        var cmd = new IdCommand();
        cmd.pomPath = parentPomPath;
        cmd.id = "a:a:1";
        cmd.as = "pom";
        cmd.run();

        cmd = new IdCommand();
        cmd.standalone = true;
        cmd.pomPath = pomPath;
        cmd.id = "b";
        cmd.run();

        assertEquals("jar unnamed:b:0.0.1-SNAPSHOT", cmd.readProjectId());
    }

    @AfterEach
    void cleanup() throws IOException {
        fs.close();
    }

}
