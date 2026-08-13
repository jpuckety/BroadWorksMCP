package co.pitayagroup.mcp.broadworks.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StorageConfigTest {

    private static final String[] NO_PROFILES = new String[0];

    @Test
    void rejectsInMemoryStorageWhenNothingAcknowledgesIt() {
        // A missing/empty broadworks.storage.backend must not silently disable encryption at rest.
        assertThatThrownBy(() -> StorageConfig.validateInMemoryUsage(NO_PROFILES, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IN_MEMORY")
                .hasMessageContaining("broadworks.storage.backend=DYNAMODB");
    }

    @Test
    void rejectsInMemoryStorageUnderAnUnrelatedProfile() {
        assertThatThrownBy(() -> StorageConfig.validateInMemoryUsage(new String[] {"prod"}, false, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsInMemoryStorageForDevelopmentProfiles() {
        for (String profile : new String[] {"dev", "local", "stdio", "test", "STDIO"}) {
            assertThatCode(() -> StorageConfig.validateInMemoryUsage(new String[] {profile}, false, false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void allowsInMemoryStorageWhenExplicitlyAcknowledged() {
        assertThatCode(() -> StorageConfig.validateInMemoryUsage(NO_PROFILES, true, false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsInMemoryStorageWhenRunningUnderTest() {
        assertThatCode(() -> StorageConfig.validateInMemoryUsage(NO_PROFILES, false, true))
                .doesNotThrowAnyException();
    }
}
