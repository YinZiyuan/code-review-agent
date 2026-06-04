import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {
    @Test
    void rejectsNegativeAmount() {
        PaymentService service = new PaymentService();
        assertThatThrownBy(() -> service.charge(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
