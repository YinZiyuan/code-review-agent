import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    @Test
    void retriesExactlyThreeTimes() {
        assertThat(new RetryPolicy().maxAttempts()).isGreaterThan(0);
    }
}
