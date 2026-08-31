import org.junit.jupiter.api.Test;

class TokenServiceTest {
    @Test
    void tokenExpiresAfterTtl() {
        new TokenService().assertExpiresAfterTtl();
    }
}
