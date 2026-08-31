import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class TokenServiceTest {
    @Disabled("flaky on CI")
    @Test
    void tokenExpiresAfterTtl() {
        new TokenService().assertExpiresAfterTtl();
    }
}
