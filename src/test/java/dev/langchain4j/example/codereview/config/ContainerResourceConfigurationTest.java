package dev.langchain4j.example.codereview.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerResourceConfigurationTest {

    @SuppressWarnings("unchecked")
    @Test
    void applicationContainerHasEffectiveCpuMemoryAndPidBounds() throws Exception {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> app = (Map<String, Object>) services.get("app");

        assertThat(app.get("mem_limit")).isEqualTo("1g");
        assertThat(app.get("cpus")).isEqualTo("1.0");
        assertThat(app.get("pids_limit")).isEqualTo(256);
    }

    @Test
    void runtimeJvmHonorsContainerMemoryAndExitsOnOom() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile)
                .contains("-XX:MaxRAMPercentage=45.0")
                .contains("-XX:MaxDirectMemorySize=128m")
                .contains("-XX:+ExitOnOutOfMemoryError");
    }
}
